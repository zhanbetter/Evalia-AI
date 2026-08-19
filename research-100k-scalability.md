# 评测平台 100K 大规模评测方案调研

## 一、基于 MinIO + Redis Bitmap 的数据集断点续传

### 现状问题
当前 `DatasetServiceImpl.upload()` 把整个文件 `readAllBytes()` 读进内存，100K 行 CSV（约 30-50MB）会占用 200-500MB 堆。

### 方案设计

**上传流程：**
```
前端：File.slice() 分片 → 逐片上传 → 检查断点 → 续传
后端：POST /init（创建 MinIO multipart + Redis session）
      POST /chunk（上传到 MinIO + SETBIT 标记）
      GET  /status（BITCOUNT 查询进度）
      POST /complete（校验 + 解析入库）
```

**核心组件：**

1. **MinIO 分片上传**：5MB/片，100K 行 CSV 约 6-10 片
   ```java
   // init: 创建 multipart upload
   minioClient.createMultipartUpload(bucket, objectName, headers, params);
   // chunk: 上传单片
   minioClient.uploadPart(bucket, objectName, uploadId, partNumber, data, size);
   // complete: 合并
   minioClient.completeMultipartUpload(bucket, objectName, uploadId, parts);
   ```

2. **Redis Bitmap 追踪**：100K 片仅 12.5KB（比 Redis Set 的 3-5MB 小 240-400 倍）
   ```bash
   SETBIT upload:{sessionId}:parts {partNum-1} 1    # 标记已上传
   BITCOUNT upload:{sessionId}:parts                  # 统计已上传数
   GETBIT upload:{sessionId}:parts {partNum-1}        # 查询单片状态
   ```

3. **流式解析**（不加载全量到内存）：
   - CSV：OpenCSV 5.9 的 `CSVReader.readNext()` 逐行读取，堆占用 ~2MB
   - XLSX：Apache POI SAX 事件模型，堆占用 ~5MB
   - 批量写库：`datasetItemMapper.saveBatch(items, 500)` 每批 500 条

### 关键收益
- 断点续传：刷新页面/网络中断后，GET /status 返回已上传分片，跳过已传部分
- O(1) 进度查询：`BITCOUNT` 不依赖数据量
- 内存安全：流式解析，100K 行峰值堆 < 10MB

---

## 二、基于 Kafka + 分片的状态机异步评测

### 现状问题
当前 `EvalWorker` 一次性 `SELECT *` 加载全量数据集，8 线程硬编码，无任务拆分。

### 方案设计

**双层状态机：**

```
TASK 级别：PENDING → RUNNING → COMPLETED / FAILED / CANCELLED
CASE 级别：PENDING → RUNNING → COMPLETED / FAILED / RETRYING / SKIPPED
```

**分片调度：**
```
100K 条数据 × 5 模型 = 500K 评测用例
÷ 500 条/片 = 1000 个分片
每个分片 = 一条 Kafka 消息
8 个消费者并发消费（按 taskId 路由到不同 partition）
```

**新表 eval_task_shard：**
```sql
CREATE TABLE eval_task_shard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    shard_index INT NOT NULL,
    shard_size INT NOT NULL,
    status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING/RUNNING/COMPLETED/FAILED
    completed_count INT DEFAULT 0,
    failed_count INT DEFAULT 0,
    retry_count INT DEFAULT 0,
    shard_data TEXT,  -- JSON: [{modelConfigId, datasetItemId}, ...]
    UNIQUE KEY uk_task_shard (task_id, shard_index)
);
```

**进度追踪（Redis Hash + HINCRBY 原子操作）：**
```java
// 初始化
redisTemplate.opsForHash().putAll("eval:task:{id}:case-progress",
    Map.of("TOTAL", "500000", "COMPLETED", "0", "FAILED", "0"));

// 每完成一条用例，原子递增
redisTemplate.opsForHash().increment(key, "COMPLETED", 1);
```

**线程池配置（Spring Bean）：**
```java
@Bean("evalTaskExecutor")
public Executor evalTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(80);     // 8 consumer × 10 并发
    executor.setMaxPoolSize(120);
    executor.setQueueCapacity(500);   // 背压
    executor.setRejectedExecutionHandler(new CallerRunsPolicy());
    return executor;
}
```

**异常隔离模式：**
```java
// 每个 case 独立 try-catch，一个失败不影响同片其他 case
for (CaseItem caseItem : shard.getCases()) {
    CompletableFuture.runAsync(() -> {
        try {
            caseExecutor.execute(task, caseItem);
            progressTracker.incrementCompleted(taskId);
        } catch (Exception e) {
            log.warn("Case失败(已隔离): {}", e.getMessage());
            progressTracker.incrementFailed(taskId);
            // 记录失败状态，供后续重试
        }
    }, evalTaskExecutor);
}
```

**Kafka 消费者（手动 ACK）：**
```java
@KafkaListener(topics = "eval-task-execute", groupId = "eval-shard-group",
    containerFactory = "kafkaManualAckContainerFactory")
public void onShard(ConsumerRecord<String, String> record, Acknowledgment ack) {
    ShardMessage msg = ShardMessage.fromJson(record.value());
    ShardProcessingResult result = shardProcessor.processShard(msg, task);
    checkTaskCompletion(task);  // 所有分片完成 → 触发 Judge
    ack.acknowledge();  // 手动确认，实现 at-least-once
}
```

### 关键收益
- 内存安全：每个分片只加载 500 条，不会 OOM
- 断点续跑：已成功的 case 不重复执行（分片级 checkpoint）
- 水平扩展：增加 Kafka partition + consumer 实例即可扩容
- 实时进度：Redis HINCRBY 毫秒级更新，前端轮询展示进度条

---

## 三、Prompt Cache + 级联裁判优化

### Prompt Cache 优化

**核心思路：** 将 prompt 拆成固定的 system 部分（缓存）和动态的 user 部分（每次变化）。

当前问题：`PromptGenerator.generateDimensionPrompt()` 生成一个扁平字符串，`splitSystemUser()` 用启发式方法事后拆分，切割点不稳定，缓存命中率低。

**改造方案：** `PromptGenerator` 新增 `generateDimensionPromptParts()` 方法，直接返回 system + user 两部分：

```
SYSTEM（静态，~1200 tokens，跨所有调用完全一致 → 缓存命中）：
  - 角色设定
  - 输出格式 / JSON Schema
  - 思维链引导
  - 评估原则

USER（动态，~800 tokens，每次调用不同）：
  - 【评测数据】渲染后的 context_template
  - 【本次评测维度】具体维度名 + rubric
  - 【额外指令】
```

**DeepSeek 自动缓存**（无需改代码）：前缀 ≥1024 tokens 自动缓存，缓存命中价仅 0.1x。

**Anthropic 缓存**：需在 system message 中加 `cache_control: {"type": "ephemeral"}` 标注。

### 级联裁判

```
Level 1（DeepSeek-V3，便宜）→ 初筛 → 检查 confidence
  ├─ confidence ≥ 3 → 直接采用 L1 结果（70% 的用例）
  └─ confidence ≤ 2 或 unknown → 触发 Level 2

Level 2（DeepSeek-V3 + CoT，更深入）→ 精审 → 采用 L2 结果（30% 的用例）
```

**输出格式增加 confidence 字段：**
```json
{"score": 4, "reason": "...", "confidence": 5}
```

**级联触发条件（三选一即触发）：**
1. confidence ≤ 2（AI 自己说不确定）
2. 输出 unknown / null（无法判断）
3. 分数处于 badcase 阈值边界（如阈值 `<3`，得分恰好为 3）

### 成本估算（100K 条 × 3 维度 = 300K 次调用）

| 方案 | 输入 Token | 输出 Token | 总费用(DeepSeek-V3) | 节省 |
|------|-----------|-----------|-------------------|------|
| 无优化 | 600M | 30M | $195 | - |
| 仅 Prompt Cache | 342M | 30M | $112 | **42.6%** |
| Cache + 级联 | 378M | 39M | $151 | **22.5%** |

Prompt Cache 是大头（省 42.6%），级联在此基础上进一步优化 5-10%。两者叠加，Token 开销降低约 20-25% 的目标可以实现。

---

## 四、基于 Embedding 的 BadCase 聚类分析

### 数据流

```
评测完成 → 筛选 verdict=BAD 的用例
  → Embedding 模型生成向量（question + model_response）
  → 层次聚类（agglomerative clustering）
  → 每个 cluster 用 LLM 分析：分类 + 归因 + 建议
  → 存储到 eval_badcase_cluster 表
  → 前端展示聚类结果
```

### 核心组件

1. **Embedding 生成**：
   - 接口：复用 OpenAI 兼容格式 `/v1/embeddings`
   - 模型选择：中文推荐 `BAAI/bge-large-zh-v1.5`（本地 Ollama 部署）或 DeepSeek Embedding API
   - 批量处理：每次 128 条，避免单次请求过大

2. **相似度聚类**（纯 Java，无需 ML 库）：
   - 输入：10K 条 badcase 的 embedding 向量（100K × 10% badcase率）
   - 算法：agglomerative clustering + average linkage + cosine distance
   - 自动阈值：取成对距离的第 25 百分位数作为合并阈值
   - 时间复杂度：O(n²) 距离矩阵，10K 条约 1 亿次操作，可接受
   - 内存：~850MB 峰值（距离矩阵）

3. **LLM 聚类分析**：
   ```
   输入：一个 cluster 的 50 条 badcase
   输出：
   {
     "cluster_summary": "模型在处理多跳推理问题时频繁遗漏中间步骤",
     "failure_type": "reasoning_error",
     "root_cause": "模型倾向于直接给出答案而跳过推理链",
     "suggestion": "在 prompt 中加入逐步推理的要求"
   }
   ```
   - 失败类型预定义 9 类：knowledge_gap、reasoning_error、format_issue、hallucination 等
   - 100 个 cluster × 2000 tokens = ~200K tokens，成本极低

### 建表
```sql
CREATE TABLE eval_badcase_cluster (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    cluster_index INT NOT NULL,
    member_count INT NOT NULL,
    failure_type VARCHAR(50),
    summary TEXT,
    root_cause TEXT,
    suggestion TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE eval_badcase_cluster_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cluster_id BIGINT NOT NULL,
    judge_result_id BIGINT NOT NULL,
    similarity_to_center DOUBLE
);
```

### 成本估算
- Embedding 生成：10K × 500 tokens = 5M tokens（DeepSeek ≈ ¥0.01）
- LLM 聚类分析：~100 cluster × 50 cases × 2000 tokens ≈ 10M tokens（≈ ¥0.02）
- **总计 < ¥0.1**

---

## 五、Redis 令牌桶限流 + 成本控制

### Redis Lua 令牌桶

```lua
-- KEYS[1] = 令牌桶key, KEYS[2] = 上次填充时间key
-- ARGV[1] = 桶容量, ARGV[2] = 填充速率(token/s), ARGV[3] = 请求token数, ARGV[4] = 当前时间ms
-- 返回: {allowed(0/1), remaining, retry_after_ms}

local tokens = tonumber(redis.call('GET', KEYS[1]))
local last_refill = tonumber(redis.call('GET', KEYS[2]))
if tokens == nil then tokens = ARGV[1]; last_refill = ARGV[4] end

local elapsed = (ARGV[4] - last_refill) / 1000.0
tokens = math.min(ARGV[1], tokens + elapsed * ARGV[2])

if tokens >= ARGV[3] then
    tokens = tokens - ARGV[3]
    redis.call('SET', KEYS[1], tokens)
    redis.call('SET', KEYS[2], ARGV[4])
    return {1, tokens, 0}
else
    local wait = math.ceil((ARGV[3] - tokens) / ARGV[2] * 1000)
    return {0, tokens, wait}
end
```

**Java 包装：**
```java
@Component
public class TokenBucketRateLimiter {
    private final StringRedisTemplate redis;
    private final RedisScript<List> luaScript;

    public boolean tryAcquire(String modelId, int qps) {
        List<Long> result = redis.execute(luaScript,
            List.of("eval:rate:" + modelId + ":tokens",
                    "eval:rate:" + modelId + ":ts"),
            "10", String.valueOf(qps), "1",
            String.valueOf(System.currentTimeMillis()));
        return result.get(0) == 1L;
    }
}
```

### 失败重试策略

```java
// 区分错误类型
public boolean shouldRetry(int httpStatus, int attempt, int maxRetries, long remainingBudget) {
    if (attempt >= maxRetries) return false;
    if (remainingBudget <= 0) return false;  // 预算耗尽不再重试
    return httpStatus == 429 || httpStatus == 500 || httpStatus == 502 || httpStatus == 503;
    // 400/401/403 不重试（永久性错误）
}

// 429 特殊处理
if (httpStatus == 429) {
    long retryAfter = parseRetryAfter(response);  // 读 Retry-After 头
    Thread.sleep(Math.max(retryAfter, 5000));     // 至少等 5s
    // 动态降速：临时降低令牌桶填充速率
    rateLimiter.reduceRate(modelId, 0.5);  // 减半
}
```

### 成本控制

```java
// Redis 记录 Token 消耗
String costKey = "eval:task:" + taskId + ":token-cost";
redisTemplate.opsForHash().increment(costKey, "prompt_tokens", response.getPromptTokens());
redisTemplate.opsForHash().increment(costKey, "completion_tokens", response.getCompletionTokens());

// 检查预算
long used = Long.parseLong(redisTemplate.opsForHash().get(costKey, "prompt_tokens").toString());
if (used > task.getTokenBudget()) {
    throw new BudgetExceededException("Token 预算已耗尽，暂停评测");
}
```

### 各模型限流配置
```yaml
eval:
  rate-limit:
    deepseek-chat: 10      # QPS
    gpt-4o: 5
    claude-3-sonnet: 5
  retry:
    max-attempts: 3
    base-delay-ms: 2000
  budget:
    default-per-task: 10000000  # 1000万 token
```

---

## 实施优先级建议

| 优先级 | 功能 | 工作量 | 面试亮点 |
|--------|------|--------|---------|
| P0 | 分片调度 + 状态机 + 异常隔离 | 3-4天 | 分布式架构核心，必讲 |
| P0 | 令牌桶限流 + 重试策略 | 1-2天 | 工程稳定性保障 |
| P1 | Prompt Cache 优化 | 1天 | 成本优化 42%，数据说话 |
| P1 | 断点续传（MinIO + Redis Bitmap） | 2天 | 大文件处理能力 |
| P2 | BadCase 聚类分析 | 2-3天 | AI 自动化分析亮点 |
| P2 | 级联裁判 | 1-2天 | 成本进一步优化 |

**面试讲述主线：**
"10万条评测 → 分片拆解成 200 个 shard → Kafka 分发到 8 个 consumer 并行消费 → 每个 shard 内 80 线程并发调 LLM → Redis 令牌桶控制 QPS 防 429 → 逐条写结果 + Redis Hash 原子更新进度 → 失败自动重试 + 手动 ACK 保证 at-least-once → 评测完成后 Embedding 聚类挖掘 BadCase → Prompt Cache + 级联裁判降低 20% Token 开销。"
