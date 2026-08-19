# Evalia-AI评测平台

面向**测试开发工程师 / 产品经理**的大模型评测（LLM-as-Judge）平台：用户编写评测 Prompt，AI 作为裁判逐条判定 badcase，系统自动计算 badcase 率并支持人工校验校准。

> 核心思路：**评测 Prompt = 一切**。维度、评分标准、badcase 判定规则全部写在 Prompt 里，AI 按 Prompt 判定，不写死业务规则。

## 生产部署

需要部署到服务器（Docker Compose 全量 + 域名 + 自动 HTTPS）？直接看 [DEPLOY.md](DEPLOY.md)，附服务器选购建议、一键部署命令与安全清单。

## 核心特性

- **单维度 AI 判定**：评估器每个维度单独调用裁判模型（消除整体判定的"光环效应"），支持聚合规则（任一/全部/比例）计算整体 badcase
- **评估器模板 + AI 润色**：内置 RAGAS 忠实度/综合、通用质量、事实准确、客服对话、代码生成等模板；自由文本可一键 AI 润色或自动转结构化维度规则
- **人工校验体系**：
  - 人机校验：AI 判定 vs 人工判定 → 一致率
  - 人人校验：多人独立判定 → 一致率 + Cohen's Kappa / Fleiss' Kappa
  - 专家裁决：有分歧时专家裁决产出**金标准**（按 模型×样本 唯一，跨任务复用）
- **评测分析**：版本对比（badcase 率升降 + 维度变化）、KPI、按维度/按模型对比、失败案例筛选
- **数据集类型标记**：`has_reference`（有/无参考答案 → 决定评估模式）、`has_model_response`（有/无模型结果 → 决定回答来源，无需时自动跳过模型调用）

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Vue3 + Element Plus + ECharts + Vite |
| 后端 | Spring Boot 2.7 + Java 17 + MyBatis Plus |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7 |
| 文件存储 | MinIO |
| 消息队列 | Kafka（评测任务异步编排） |

## 项目结构

```
llm-eval-server/
├── llm-eval-common   # 常量、工具（Kafka Topic、AES 加解密）
├── llm-eval-model    # 实体类
├── llm-eval-dao      # MyBatis Plus Mapper
├── llm-eval-service  # 业务逻辑 + Harness（DatasetAdapter / Metric / LlmJudgeMetric）
├── llm-eval-worker   # KafKa 消费者（EvalWorker 跑模型生成回答，JudgeWorker 做 AI 判定）
├── llm-eval-web      # 启动入口 + Controller（唯一 Spring Boot 应用，扫描 com.eval）
├── sql/              # init.sql（完整基线，含全部表）+ 增量迁移脚本
├── sample-datasets/  # 示例数据集（数学推理 / 客服对话 / 代码生成 / 知识问答 / GSM8K）
└── docker-compose.yml
llm-eval-ui/          # Vue3 前端（页面：数据集/模型/评估器/任务/评测分析/人工校验）
scripts/              # 一键启动脚本（Windows）
```

## 快速开始

### 前置要求

- JDK 17、Maven 3.8+、Node 18+
- Docker Desktop（用于 MySQL / Redis / MinIO / Kafka）

### 方式一：一键启动（Windows）

双击运行根目录 `scripts/start-dev.cmd`，自动完成：确保 Kafka → 编译并启动后端（8080）→ 启动前端（3000）→ 打开浏览器。

### 方式二：手动启动

```bash
# 1. 启动基础设施（MySQL / Redis / MinIO / Kafka）
cd llm-eval-server
docker compose up -d

# 2. 首次使用：MinIO 控制台 http://localhost:9001（minioadmin/minioadmin）
#    创建 bucket: llm-eval

# 3. 启动后端
cd llm-eval-server
mvn clean package -DskipTests
java -jar llm-eval-web/target/llm-eval-web-1.0.0-SNAPSHOT.jar

# 4. 启动前端
cd llm-eval-ui
npm install
npm run dev
```

访问 http://localhost:3000 。示例评测数据集在 `llm-eval-server/sample-datasets/`。

### 使用流程

1. **上传数据集**，标记评测类型（含/无参考答案）与模型结果（已含/需现场生成）
2. **配置模型**：被测模型 + 裁判模型（OpenAI 兼容接口）
3. **编写评估器**：从模板创建，或自由文本 + AI 润色 / 转维度
4. **创建评测任务**：选数据集 + 被测模型 + 评估器（评估器暂需单选）
5. 任务异步执行：Kafka Producer → EvalWorker（生成回答/读取回答）→ JudgeWorker（逐维度判定）→ 汇总
6. 在**评测分析**页查看版本对比、badcase 率、维度表现、失败案例
7. 在**人工校验**页做人工判定，校准 AI，产出金标准

## 评测 Prompt 约定

AI 需返回 JSON：

```json
{"is_badcase": true/false, "dimensions": ["维度1","维度2"], "reason": "判定理由"}
```

- `is_badcase`：该条回复是否为 badcase
- `dimensions`：触发的维度列表（用于按维度统计）
- `reason`：判定理由

模板占位符：`{question}`、`{reference_answer}`、`{model_response}`、`{context}`。结构化评估器使用 `dimensions_config`，每个维度单独调用裁判模型一次。

## 配置与安全

- 默认连接本地 `localhost`，数据库口令见 `llm-eval-web/src/main/resources/application.yml`（与 `docker-compose.yml` 一致）
- 模型 API Key 使用 AES-256-GCM 加密存储（`EncryptUtil`），未配置 `EVAL_ENCRYPT_KEY` 时使用代码内默认开发密钥——**生产环境请务必通过 `EVAL_ENCRYPT_KEY` 环境变量配置独立密钥**
- Kafka 消费者默认随应用启动（`spring.kafka.listener.auto-startup=true`），无 Kafka 时不影响应用启动，但评测任务无法执行

## License

MIT