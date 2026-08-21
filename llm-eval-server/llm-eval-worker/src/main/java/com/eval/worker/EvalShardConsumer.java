package com.eval.worker;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.constant.KafkaTopic;
import com.eval.common.constant.ShardStatus;
import com.eval.common.constant.TaskStatus;
import com.eval.dao.mapper.*;
import com.eval.model.dto.CaseItem;
import com.eval.model.dto.ShardMessage;
import com.eval.model.entity.*;
import com.eval.service.LlmApiClient;
import com.eval.service.harness.DatasetAdapter;
import com.eval.service.harness.EvalSample;
import com.eval.service.harness.ExactMatchMetric;
import com.eval.service.harness.KeywordMatchMetric;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 分片评测 Consumer
 * 每条消息对应一个分片（最多500条case），分片之间完全隔离：
 * 单个分片失败不影响其他分片，异常被限制在分片内部
 *
 * 核心流程：
 *   1. 解析分片消息 → 加载任务上下文
 *   2. 获取分片级分布式锁（防止重复消费）
 *   3. 逐条执行 case（含重试）
 *   4. 更新分片进度 → 检查是否所有分片完成 → 触发 Judge
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalShardConsumer {

    private final EvalTaskMapper taskMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalTaskShardMapper shardMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final EvalDatasetSchemaMapper schemaMapper;
    private final EvalResultMapper resultMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DatasetAdapter datasetAdapter;

    private static final String SHARD_LOCK_PREFIX = "eval:shard:";
    private static final String LOCK_SUFFIX = ":lock";
    private static final long LOCK_TTL_MINUTES = 15;
    private static final int MAX_RETRIES = 3;
    /** 分片级最大重试次数（分片内单条 case 已有 MAX_RETRIES 次重试，分片级再给一次机会） */
    private static final int MAX_SHARD_RETRIES = 2;
    private static final int SHARD_SIZE = 500;

    @KafkaListener(topics = KafkaTopic.EVAL_SHARD_EXECUTE, groupId = "eval-shard-group")
    public void onShard(String messageJson) {
        ShardMessage msg;
        try {
            msg = JSONUtil.toBean(messageJson, ShardMessage.class);
        } catch (Exception e) {
            log.error("分片消息解析失败: {}", messageJson, e);
            return;
        }

        Long taskId = msg.getTaskId();
        int shardIndex = msg.getShardIndex();
        log.info("收到分片消息: taskId={}, shardIndex={}, caseCount={}", taskId, shardIndex, msg.getTotalCount());

        // 1. 检查任务是否已取消
        EvalTask task = taskMapper.selectById(taskId);
        if (task == null || TaskStatus.CANCELLED.equals(task.getStatus())) {
            log.info("任务不存在或已取消，跳过分片: taskId={}, shard={}", taskId, shardIndex);
            markShardSkipped(taskId, shardIndex);
            return;
        }

        // 2. 获取分片级分布式锁（防止同一分片被重复消费）
        String lockKey = SHARD_LOCK_PREFIX + taskId + ":shard-" + shardIndex + LOCK_SUFFIX;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL_MINUTES, TimeUnit.MINUTES);
        if (!acquired) {
            log.warn("分片已被其他 Consumer 锁定，跳过: taskId={}, shard={}", taskId, shardIndex);
            return;
        }

        try {
            executeShard(task, msg);
        } catch (Exception e) {
            log.error("分片执行异常: taskId={}, shard={}", taskId, shardIndex, e);
            failShard(taskId, shardIndex, e.getMessage());
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 执行单个分片：加载上下文 → 逐条执行 → 更新进度
     */
    private void executeShard(EvalTask task, ShardMessage msg) {
        Long taskId = task.getId();
        int shardIndex = msg.getShardIndex();

        // 更新分片状态为 RUNNING
        EvalTaskShard shard = getOrCreateShard(taskId, shardIndex, msg);
        if (ShardStatus.COMPLETED.equals(shard.getStatus())) {
            log.info("分片已完成（幂等跳过）: taskId={}, shard={}", taskId, shardIndex);
            return;
        }

        // 分片级重试：删除上一次失败的 ERROR 结果，让断点续评不跳过这些 case
        if (ShardStatus.FAILED.equals(shard.getStatus())) {
            Set<Long> failedItemIds = msg.getCases().stream()
                    .map(CaseItem::getDatasetItemId).collect(Collectors.toSet());
            if (!failedItemIds.isEmpty()) {
                resultMapper.delete(new LambdaQueryWrapper<EvalResult>()
                        .eq(EvalResult::getTaskId, taskId)
                        .likeRight(EvalResult::getResponse, "ERROR:")
                        .in(EvalResult::getDatasetItemId, failedItemIds));
                log.info("分片重试：已清理上次失败结果: taskId={}, shard={}", taskId, shardIndex);
            }
        }

        shard.setStatus(ShardStatus.RUNNING);
        shard.setCompletedCount(0);
        shard.setFailedCount(0);
        shardMapper.updateById(shard);

        // 加载任务上下文
        List<EvalTaskModel> taskModels = taskModelMapper.selectList(
                new LambdaQueryWrapper<EvalTaskModel>().eq(EvalTaskModel::getTaskId, taskId));
        List<EvalModelConfig> models = new ArrayList<>();
        for (EvalTaskModel tm : taskModels) {
            EvalModelConfig config = modelConfigMapper.selectById(tm.getModelConfigId());
            if (config != null) models.add(config);
        }
        if (models.isEmpty()) {
            log.warn("无可用模型，跳过分片: taskId={}, shard={}", taskId, shardIndex);
            markShardDone(shard, 0, 0);
            return;
        }

        // 构建 modelConfigId → EvalModelConfig 映射
        Map<Long, EvalModelConfig> modelMap = new HashMap<>();
        for (EvalModelConfig m : models) {
            modelMap.put(m.getId(), m);
        }

        // 查询数据集 schema
        List<EvalDatasetSchema> schemaFields = schemaMapper.selectList(
                new LambdaQueryWrapper<EvalDatasetSchema>()
                        .eq(EvalDatasetSchema::getDatasetId, task.getDatasetId()));

        // 解析字段映射
        Map<String, String> mapping = datasetAdapter.parseFieldMapping(task.getFieldMapping());

        // 批量查询分片涉及的 dataset items（减少 DB 查询）
        Set<Long> itemIds = new HashSet<>();
        for (CaseItem c : msg.getCases()) {
            itemIds.add(c.getDatasetItemId());
        }
        Map<Long, EvalDatasetItem> itemMap = new HashMap<>();
        if (!itemIds.isEmpty()) {
            List<EvalDatasetItem> items = datasetItemMapper.selectBatchIds(itemIds);
            for (EvalDatasetItem item : items) {
                itemMap.put(item.getId(), item);
            }
        }

        // 断点续评：查询已完成的 case 组合
        List<Long> caseIds = new ArrayList<>();
        for (CaseItem c : msg.getCases()) {
            caseIds.add(c.getModelConfigId() * 100000L + c.getDatasetItemId()); // 唯一键（不入库，仅用于跳过判断）
        }
        // 简单做法：查询该分片已有的 result 记录
        Set<String> doneKeys = getDoneKeys(taskId, msg.getCases());

        // 逐条执行 case
        int completedCount = 0;
        int failedCount = 0;
        for (CaseItem caseItem : msg.getCases()) {
            // 定期检查任务是否已取消
            EvalTask latestTask = taskMapper.selectById(taskId);
            if (latestTask != null && TaskStatus.CANCELLED.equals(latestTask.getStatus())) {
                log.info("任务已取消，停止分片执行: taskId={}, shard={}", taskId, shardIndex);
                return;
            }

            String doneKey = caseItem.getModelConfigId() + "_" + caseItem.getDatasetItemId();
            if (doneKeys.contains(doneKey)) {
                completedCount++; // 断点续评：跳过已完成的
                continue;
            }

            EvalDatasetItem item = itemMap.get(caseItem.getDatasetItemId());
            EvalModelConfig model = modelMap.get(caseItem.getModelConfigId());
            if (item == null || model == null) {
                log.warn("数据集条目或模型不存在，跳过: itemId={}, modelId={}",
                        caseItem.getDatasetItemId(), caseItem.getModelConfigId());
                failedCount++;
                continue;
            }

            boolean success = executeOneCase(task, item, model, mapping, schemaFields);
            if (success) {
                completedCount++;
            } else {
                failedCount++;
            }

            // 实时更新分片进度
            shard.setCompletedCount(completedCount + failedCount);
            shard.setFailedCount(failedCount);
            shardMapper.updateById(shard);
        }

        // 分片完成
        markShardDone(shard, completedCount, failedCount);
        log.info("分片执行完成: taskId={}, shard={}, completed={}, failed={}",
                taskId, shardIndex, completedCount, failedCount);

        // 检查是否所有分片完成 → 触发 Judge
        checkAndTriggerJudge(task);
    }

    /**
     * 执行单条 case（含重试）
     * 返回 true=成功，false=失败
     */
    private boolean executeOneCase(EvalTask task, EvalDatasetItem item, EvalModelConfig model,
                                   Map<String, String> mapping, List<EvalDatasetSchema> schemaFields) {
        Long taskId = task.getId();
        EvalSample sample = datasetAdapter.toSample(item, null);

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                EvalResult result = new EvalResult();
                result.setTaskId(taskId);
                result.setModelConfigId(model.getId());
                result.setDatasetItemId(item.getId());
                result.setCreatedAt(LocalDateTime.now());

                boolean callApi = "api".equals(task.getAnswerSource()) && StrUtil.isNotBlank(task.getPromptTemplate());
                if (callApi) {
                    String prompt = datasetAdapter.renderPrompt(task.getPromptTemplate(), sample, mapping);
                    LlmApiClient.ChatResponse chatResponse = llmApiClient.chatWithUsage(model, prompt);
                    result.setPrompt(prompt);
                    result.setResponse(chatResponse.getContent());
                    result.setLatencyMs(chatResponse.getLatencyMs());
                    result.setTokenUsage(chatResponse.getTotalTokens());
                    sample.setModelResponse(chatResponse.getContent());
                    result.setExactMatchScore((Integer) new ExactMatchMetric().evaluate(sample, null).getScore());
                    result.setKeywordMatchScore((Integer) new KeywordMatchMetric().evaluate(sample, null).getScore());
                } else {
                    String datasetResponse = datasetAdapter.extractResponseFromItem(item, schemaFields);
                    sample.setModelResponse(datasetResponse != null ? datasetResponse : "");
                    result.setPrompt("");
                    result.setResponse(sample.getModelResponse());
                    result.setLatencyMs(0);
                    result.setTokenUsage(0);
                    result.setExactMatchScore(0);
                    result.setKeywordMatchScore(0);
                }
                resultMapper.insert(result);

                // 更新全局进度（Redis Hash 原子递增）
                String progressKey = "eval:task:" + taskId + ":progress";
                redisTemplate.opsForHash().increment(progressKey, "COMPLETED", 1);
                return true;

            } catch (Exception e) {
                if (attempt < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                // 最终失败：记录 ERROR 结果
                log.error("case 执行最终失败: taskId={}, model={}, itemId={}", taskId, model.getId(), item.getId(), e);
                try {
                    EvalResult errorResult = new EvalResult();
                    errorResult.setTaskId(taskId);
                    errorResult.setModelConfigId(model.getId());
                    errorResult.setDatasetItemId(item.getId());
                    errorResult.setPrompt("(无调用Prompt)");
                    errorResult.setResponse("ERROR: " + e.getMessage());
                    errorResult.setExactMatchScore(0);
                    errorResult.setKeywordMatchScore(0);
                    errorResult.setCreatedAt(LocalDateTime.now());
                    resultMapper.insert(errorResult);
                } catch (Exception insertEx) {
                    log.error("错误结果入库失败: taskId={}, model={}, itemId={}", taskId, model.getId(), item.getId(), insertEx);
                }
                String progressKey = "eval:task:" + taskId + ":progress";
                redisTemplate.opsForHash().increment(progressKey, "FAILED", 1);
                return false;
            }
        }
        return false;
    }

    /**
     * 检查是否所有分片已完成，如果是则触发 Judge 阶段
     * 使用 Redis 分布式锁防止并发分片同时触发 Judge
     */
    private void checkAndTriggerJudge(EvalTask task) {
        Long taskId = task.getId();

        // 分布式锁：同一任务只允许一个分片触发 Judge 检查
        String lockKey = "eval:task:" + taskId + ":judge-trigger-lock";
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);
        if (!acquired) {
            log.info("Judge 触发锁已被占用，跳过: taskId={}", taskId);
            return;
        }

        // 统计分片完成数（COMPLETED 或 FAILED 都算终态）
        long terminalCount = shardMapper.selectCount(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .in(EvalTaskShard::getStatus, ShardStatus.COMPLETED, ShardStatus.FAILED));

        int totalShards = task.getShardCount() != null ? task.getShardCount() : 0;
        if (totalShards <= 0) {
            // 兼容旧任务（无分片），直接触发 Judge
            triggerJudge(task);
            return;
        }

        if (terminalCount < totalShards) {
            log.info("分片未全部完成: taskId={}, {}/{}", taskId, terminalCount, totalShards);
            redisTemplate.delete(lockKey);
            return;
        }

        // 所有分片终态 → 检查是否有可重试的 FAILED 分片
        List<EvalTaskShard> failedShards = shardMapper.selectList(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .eq(EvalTaskShard::getStatus, ShardStatus.FAILED));
        boolean hasRetriable = false;
        for (EvalTaskShard fs : failedShards) {
            if (fs.getRetryCount() == null || fs.getRetryCount() < 2) {
                hasRetriable = true;
                break;
            }
        }
        if (hasRetriable) {
            // 有可重试分片 → 重新发送到 Kafka，不触发 Judge
            for (EvalTaskShard fs : failedShards) {
                if (fs.getRetryCount() != null && fs.getRetryCount() >= 2) continue;
                try {
                    List<CaseItem> cases = cn.hutool.json.JSONUtil.toList(fs.getShardData(), CaseItem.class);
                    ShardMessage retryMsg = new ShardMessage();
                    retryMsg.setTaskId(taskId);
                    retryMsg.setShardIndex(fs.getShardIndex());
                    retryMsg.setTotalCount(fs.getShardSize());
                    retryMsg.setCases(cases);
                    kafkaTemplate.send(KafkaTopic.EVAL_SHARD_EXECUTE, String.valueOf(taskId),
                            cn.hutool.json.JSONUtil.toJsonStr(retryMsg));
                    log.info("分片已重新调度: taskId={}, shard={}", taskId, fs.getShardIndex());
                } catch (Exception e) {
                    log.error("分片重新调度失败: taskId={}, shard={}", taskId, fs.getShardIndex(), e);
                }
            }
            redisTemplate.delete(lockKey);
            return;
        }

        // 统计失败分片数（已达重试上限的）
        long terminalFailedCount = shardMapper.selectCount(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .eq(EvalTaskShard::getStatus, ShardStatus.FAILED)
                        .ge(EvalTaskShard::getRetryCount, 2));

        // 所有分片终态 → 更新任务状态
        if (terminalFailedCount == totalShards) {
            // 全部分片失败 → 任务失败
            task.setStatus(TaskStatus.FAILED);
        } else {
            // 至少有一个分片成功 → 任务完成（失败 case 已记录 ERROR 结果）
            task.setStatus(TaskStatus.COMPLETED);
        }
        task.setProgress(100);
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        // 触发 Judge 阶段
        triggerJudge(task);

        // 释放锁
        redisTemplate.delete(lockKey);

        log.info("所有分片完成，Judge 已触发: taskId={}, total={}, completed={}, failed={}",
                taskId, totalShards, totalShards - terminalFailedCount, terminalFailedCount);
    }

    /**
     * 触发 Judge 评分（带失败补偿）
     */
    private void triggerJudge(EvalTask task) {
        try {
            kafkaTemplate.send(KafkaTopic.EVAL_TASK_JUDGE, String.valueOf(task.getId()))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Judge消息发送失败，已标记待补偿: taskId={}", task.getId(), e);
            redisTemplate.opsForValue().set(
                    "eval:task:" + task.getId() + ":pending-judge", "1", 7, TimeUnit.DAYS);
        }
    }

    // ===== 工具方法 =====

    private EvalTaskShard getOrCreateShard(Long taskId, int shardIndex, ShardMessage msg) {
        EvalTaskShard shard = shardMapper.selectOne(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .eq(EvalTaskShard::getShardIndex, shardIndex));
        if (shard == null) {
            shard = new EvalTaskShard();
            shard.setTaskId(taskId);
            shard.setShardIndex(shardIndex);
            shard.setShardSize(msg.getTotalCount());
            shard.setStatus(ShardStatus.PENDING);
            shard.setCompletedCount(0);
            shard.setFailedCount(0);
            shard.setRetryCount(0);
            shard.setShardData(JSONUtil.toJsonStr(msg.getCases()));
            shard.setCreatedAt(LocalDateTime.now());
            shardMapper.insert(shard);
        }
        return shard;
    }

    private void markShardDone(EvalTaskShard shard, int completedCount, int failedCount) {
        shard.setCompletedCount(completedCount);
        shard.setFailedCount(failedCount);
        shard.setFinishedAt(LocalDateTime.now());
        if (failedCount > 0 && (shard.getRetryCount() == null || shard.getRetryCount() < MAX_SHARD_RETRIES)) {
            // 有失败且重试次数未达上限 → 标记 FAILED，等待分片级重试
            shard.setStatus(ShardStatus.FAILED);
            shard.setRetryCount((shard.getRetryCount() != null ? shard.getRetryCount() : 0) + 1);
            log.warn("分片有失败 case，将重试: taskId={}, shard={}, failed={}, retry={}/{}",
                    shard.getTaskId(), shard.getShardIndex(), failedCount,
                    shard.getRetryCount(), MAX_SHARD_RETRIES);
        } else {
            // 全部成功，或已达重试上限 → 标记 COMPLETED（终态）
            shard.setStatus(ShardStatus.COMPLETED);
        }
        shardMapper.updateById(shard);
    }

    private void markShardSkipped(Long taskId, int shardIndex) {
        EvalTaskShard shard = shardMapper.selectOne(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .eq(EvalTaskShard::getShardIndex, shardIndex));
        if (shard != null && !ShardStatus.COMPLETED.equals(shard.getStatus())) {
            shard.setStatus(ShardStatus.COMPLETED);
            shard.setFinishedAt(LocalDateTime.now());
            shardMapper.updateById(shard);
        }
    }

    private void failShard(Long taskId, int shardIndex, String errorMsg) {
        EvalTaskShard shard = shardMapper.selectOne(
                new LambdaQueryWrapper<EvalTaskShard>()
                        .eq(EvalTaskShard::getTaskId, taskId)
                        .eq(EvalTaskShard::getShardIndex, shardIndex));
        if (shard != null) {
            shard.setStatus(ShardStatus.FAILED);
            shard.setErrorMessage(errorMsg != null ? errorMsg.substring(0, Math.min(errorMsg.length(), 500)) : "未知错误");
            shard.setFinishedAt(LocalDateTime.now());
            shardMapper.updateById(shard);
        }
    }

    /**
     * 查询分片内已完成的 case（用于断点续评）
     */
    private Set<String> getDoneKeys(Long taskId, List<CaseItem> cases) {
        if (cases.isEmpty()) return Collections.emptySet();

        // 批量查询该分片涉及的所有 case 的 result
        List<EvalResult> existingResults = resultMapper.selectList(
                new LambdaQueryWrapper<EvalResult>()
                        .eq(EvalResult::getTaskId, taskId));

        Set<String> doneKeys = new HashSet<>();
        for (EvalResult r : existingResults) {
            doneKeys.add(r.getModelConfigId() + "_" + r.getDatasetItemId());
        }

        // 只保留本分片相关的
        Set<String> shardKeys = new HashSet<>();
        for (CaseItem c : cases) {
            String key = c.getModelConfigId() + "_" + c.getDatasetItemId();
            if (doneKeys.contains(key)) {
                shardKeys.add(key);
            }
        }
        return shardKeys;
    }
}
