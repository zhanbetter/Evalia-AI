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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final EvalResultMapper resultMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DatasetAdapter datasetAdapter;

    @KafkaListener(topics = KafkaTopic.EVAL_TASK_EXECUTE, groupId = "eval-worker-group")
    public void onEvalTask(String taskIdStr) {
        Long taskId = Long.parseLong(taskIdStr);
        log.info("收到评测任务: taskId={}", taskId);

        EvalTask task = taskMapper.selectById(taskId);
        if (task == null || !TaskStatus.RUNNING.equals(task.getStatus())) {
            log.warn("任务不存在或状态不正确: taskId={}", taskId);
            return;
        }

        try {
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

            int totalTasks = items.size() * models.size();
            String progressKey = "eval:task:" + taskId + ":progress";

            // 解析字段映射
            java.util.Map<String, String> mapping = datasetAdapter.parseFieldMapping(task.getFieldMapping());

            // ===== 断点续评：查询已完成的 (modelConfigId, datasetItemId) 组合，跳过 =====
            List<EvalResult> existingResults = resultMapper.selectList(
                    new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, taskId));
            Set<String> doneKeys = existingResults.stream()
                    .map(r -> r.getModelConfigId() + "_" + r.getDatasetItemId())
                    .collect(Collectors.toSet());

            // ===== 并发执行评测 =====
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
                            try {
                                executeOneEval(taskId, item, model, mapping, task.getPromptTemplate(), task.getAnswerSource());
                            } catch (Exception e) {
                                log.error("评测条目执行异常(已捕获): taskId={}, model={}, itemId={}", taskId, model.getModelId(), item.getId(), e);
                            }
                        }, executor));
                    }
                }

                // 等待所有完成
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } finally {
                executor.shutdown();
            }

            // 完成后重新统计已完成数量（含断点续评跳过的）
            List<EvalResult> finalResults = resultMapper.selectList(
                    new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, taskId));
            int completed = finalResults.size();
            redisTemplate.opsForValue().set(progressKey, completed + "/" + totalTasks);

            // 4. 任务完成，触发 Judge 评分
            task.setStatus(TaskStatus.COMPLETED);
            task.setProgress(100);
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            // 发送 Judge 评分消息
            kafkaTemplate.send(KafkaTopic.EVAL_TASK_JUDGE, String.valueOf(taskId));

            log.info("评测任务执行完成: taskId={}, total={}, completed={}", taskId, totalTasks, completed);

        } catch (Exception e) {
            log.error("评测任务执行异常: taskId={}", taskId, e);
            task.setStatus(TaskStatus.FAILED);
            taskMapper.updateById(task);
        }
    }

    /**
     * 执行单条评测（含指数退避重试）
     * 注意：在并发线程中执行，不能使用事务注解
     */
    private void executeOneEval(Long taskId, EvalDatasetItem item, EvalModelConfig model,
                                java.util.Map<String, String> mapping, String promptTemplate, String answerSource) {
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
                    String datasetResponse = datasetAdapter.extractResponseFromItem(item);
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
