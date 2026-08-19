package com.eval.worker;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.constant.KafkaTopic;
import com.eval.common.constant.TaskStatus;
import com.eval.dao.mapper.*;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 评测执行 Worker
 * 消费 Kafka 消息，执行模型调用 + 客观评分
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalWorker {

    private final EvalTaskMapper taskMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final EvalDatasetSchemaMapper schemaMapper;
    private final EvalResultMapper resultMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DatasetAdapter datasetAdapter;

    private static final String LOCK_SUFFIX = ":exec-lock";
    private static final String PROGRESS_SUFFIX = ":progress";
    private static final long LOCK_TTL_MINUTES = 30;

    @KafkaListener(topics = KafkaTopic.EVAL_TASK_EXECUTE, groupId = "eval-worker-group")
    public void onEvalTask(String taskIdStr) {
        Long taskId = Long.parseLong(taskIdStr);
        log.info("收到评测任务: taskId={}", taskId);

        // ===== 分布式锁：防止同一任务被多个 Worker 重复执行 =====
        String lockKey = "eval:task:" + taskId + LOCK_SUFFIX;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL_MINUTES, TimeUnit.MINUTES);
        if (!acquired) {
            log.warn("任务已被其他 Worker 锁定，跳过: taskId={}", taskId);
            return;
        }

        EvalTask task = null;
        try {
            task = taskMapper.selectById(taskId);
            if (task == null || !TaskStatus.RUNNING.equals(task.getStatus())) {
                log.warn("任务不存在或状态不正确: taskId={}", taskId);
                return;
            }

            // 1. 查询关联的模型
            List<EvalTaskModel> taskModels = taskModelMapper.selectList(
                    new LambdaQueryWrapper<EvalTaskModel>().eq(EvalTaskModel::getTaskId, taskId));
            List<EvalModelConfig> models = new ArrayList<>();
            for (EvalTaskModel tm : taskModels) {
                EvalModelConfig config = modelConfigMapper.selectById(tm.getModelConfigId());
                if (config != null) {
                    models.add(config);
                }
            }

            // 2. 查询数据集条目
            List<EvalDatasetItem> items = datasetItemMapper.selectList(
                    new LambdaQueryWrapper<EvalDatasetItem>()
                            .eq(EvalDatasetItem::getDatasetId, task.getDatasetId())
                            .orderByAsc(EvalDatasetItem::getSeqNo));

            // 2.5 查询数据集 schema（用于增强模型回答字段识别）
            List<EvalDatasetSchema> schemaFields = schemaMapper.selectList(
                    new LambdaQueryWrapper<EvalDatasetSchema>()
                            .eq(EvalDatasetSchema::getDatasetId, task.getDatasetId()));

            int totalTasks = items.size() * models.size();
            String progressKey = "eval:task:" + taskId + PROGRESS_SUFFIX;

            // 解析字段映射
            java.util.Map<String, String> mapping = datasetAdapter.parseFieldMapping(task.getFieldMapping());
            final EvalTask finalTask = task;

            // ===== 断点续评：查询已完成的 (modelConfigId, datasetItemId) 组合，跳过 =====
            List<EvalResult> existingResults = resultMapper.selectList(
                    new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, taskId));
            Set<String> doneKeys = existingResults.stream()
                    .map(r -> r.getModelConfigId() + "_" + r.getDatasetItemId())
                    .collect(Collectors.toSet());

            // 已跳过的数量（断点续评跳过的 + 之前失败的）
            int skippedCount = doneKeys.size();
            if (skippedCount > 0) {
                // 将已完成/跳过的计入进度（避免进度卡在 0%）
                redisTemplate.opsForHash().increment(progressKey, "COMPLETED", skippedCount);
            }

            // ===== 并发执行评测 =====
            AtomicInteger completedCount = new AtomicInteger(skippedCount);
            AtomicInteger failedCount = new AtomicInteger(0);
            ExecutorService executor = Executors.newFixedThreadPool(8);
            try {
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (EvalDatasetItem item : items) {
                    for (EvalModelConfig model : models) {
                        String key = model.getId() + "_" + item.getId();
                        if (doneKeys.contains(key)) {
                            continue; // 跳过已完成的（断点续评）
                        }
                        futures.add(CompletableFuture.runAsync(() -> {
                            // ===== 定期检查任务是否已取消（每条 case 前检查） =====
                            EvalTask latestTask = taskMapper.selectById(taskId);
                            if (latestTask != null && TaskStatus.CANCELLED.equals(latestTask.getStatus())) {
                                log.info("任务已取消，停止执行: taskId={}", taskId);
                                return;
                            }
                            try {
                                executeOneEval(taskId, item, model, mapping,
                                        finalTask.getPromptTemplate(), finalTask.getAnswerSource(), schemaFields);
                                // 每条成功后原子递增进度
                                long done = redisTemplate.opsForHash().increment(progressKey, "COMPLETED", 1);
                                completedCount.incrementAndGet();
                            } catch (Exception e) {
                                log.error("评测条目执行异常(已捕获): taskId={}, model={}, itemId={}",
                                        taskId, model.getModelId(), item.getId(), e);
                                redisTemplate.opsForHash().increment(progressKey, "FAILED", 1);
                                failedCount.incrementAndGet();
                            }
                        }, executor));
                    }
                }

                // 等待所有完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }

            // 4. 任务完成，触发 Judge 评分
            task.setStatus(TaskStatus.COMPLETED);
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // 发送 Judge 评分消息（带失败补偿）
            try {
                kafkaTemplate.send(KafkaTopic.EVAL_TASK_JUDGE, String.valueOf(taskId))
                        .get(10, TimeUnit.SECONDS); // 同步等待发送结果
            } catch (Exception e) {
                log.error("Judge消息发送失败，已标记待补偿: taskId={}", taskId, e);
                // Redis 补偿标记：后续定时任务或手动重试可检查此 key
                redisTemplate.opsForValue().set(
                        "eval:task:" + taskId + ":pending-judge", "1", 7, TimeUnit.DAYS);
            }

            log.info("评测任务执行完成: taskId={}, total={}, completed={}, failed={}",
                    taskId, totalTasks, completedCount.get(), failedCount.get());

        } catch (Exception e) {
            log.error("评测任务执行异常: taskId={}", taskId, e);
            if (task != null) {
                task.setStatus(TaskStatus.FAILED);
                task.setFinishedAt(LocalDateTime.now());
                taskMapper.updateById(task);
            }
        } finally {
            // 释放分布式锁
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 执行单条评测（含指数退避重试）
     * 注意：在并发线程中执行，不能使用事务注解
     */
    private void executeOneEval(Long taskId, EvalDatasetItem item, EvalModelConfig model,
                                java.util.Map<String, String> mapping, String promptTemplate, String answerSource,
                                List<EvalDatasetSchema> schemaFields) {
        // 归一化为统一样本
        EvalSample sample = datasetAdapter.toSample(item, null);

        int maxRetries = 3;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                EvalResult result = new EvalResult();
                result.setTaskId(taskId);
                result.setModelConfigId(model.getId());
                result.setDatasetItemId(item.getId());
                result.setCreatedAt(LocalDateTime.now());

                boolean callApi = "api".equals(answerSource) && StrUtil.isNotBlank(promptTemplate);
                if (callApi) {
                    // ===== 现场调模型生成回答 =====
                    String prompt = datasetAdapter.renderPrompt(promptTemplate, sample, mapping);
                    LlmApiClient.ChatResponse chatResponse = llmApiClient.chatWithUsage(model, prompt);
                    result.setPrompt(prompt);
                    result.setResponse(chatResponse.getContent());
                    result.setLatencyMs(chatResponse.getLatencyMs());
                    result.setTokenUsage(chatResponse.getTotalTokens());
                    sample.setModelResponse(chatResponse.getContent());
                    result.setExactMatchScore((Integer) new ExactMatchMetric().evaluate(sample, null).getScore());
                    result.setKeywordMatchScore((Integer) new KeywordMatchMetric().evaluate(sample, null).getScore());
                } else {
                    // ===== 数据集已有回答：从 extra_fields 读取 model_response =====
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
                return; // 成功，返回

            } catch (Exception e) {
                if (attempt < maxRetries - 1) {
                    // 指数退避：1s, 2s, 4s
                    try {
                        Thread.sleep((long) Math.pow(2, attempt) * 1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                // 最终失败：记录 ERROR 结果
                log.error("评测条目最终失败: taskId={}, model={}, itemId={}", taskId, model.getModelId(), item.getId(), e);
                EvalResult result = new EvalResult();
                result.setTaskId(taskId);
                result.setModelConfigId(model.getId());
                result.setDatasetItemId(item.getId());
                result.setPrompt("(无调用Prompt)");
                result.setResponse("ERROR: " + e.getMessage());
                result.setExactMatchScore(0);
                result.setKeywordMatchScore(0);
                result.setCreatedAt(LocalDateTime.now());
                resultMapper.insert(result);
            }
        }
    }
}
