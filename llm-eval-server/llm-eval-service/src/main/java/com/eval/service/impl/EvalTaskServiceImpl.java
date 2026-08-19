package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.constant.KafkaTopic;
import com.eval.common.constant.ShardStatus;
import com.eval.common.constant.TaskStatus;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.*;
import com.eval.model.dto.CaseItem;
import com.eval.model.dto.EvalTaskDTO;
import com.eval.model.dto.ShardMessage;
import com.eval.model.entity.*;
import com.eval.service.EvalTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalTaskServiceImpl implements EvalTaskService {

    private static final int SHARD_SIZE = 500;

    private final EvalTaskMapper taskMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalTaskPromptMapper taskPromptMapper;
    private final EvalDatasetMapper datasetMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final EvalPromptMapper promptMapper;
    private final EvalTaskShardMapper shardMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Override
    @Transactional
    public EvalTask create(EvalTaskDTO dto) {
        EvalDataset dataset = datasetMapper.selectById(dto.getDatasetId());
        if (dataset == null) throw new BusinessException("数据集不存在");

        for (Long modelId : dto.getModelConfigIds()) {
            EvalModelConfig config = modelConfigMapper.selectById(modelId);
            if (config == null || config.getStatus() != 1) throw new BusinessException("模型不存在或已禁用: " + modelId);
        }

        for (Long promptId : dto.getPromptIds()) {
            EvalPrompt prompt = promptMapper.selectById(promptId);
            if (prompt == null || prompt.getStatus() != 1) throw new BusinessException("评测Prompt不存在或已禁用: " + promptId);

            // 校验评测模式与数据集是否有参考答案匹配
            // reference模式需要参考答案，但数据集没有 → 结果会失真
            if ("reference".equals(prompt.getEvaluationMode())
                    && (dataset.getHasReference() == null || dataset.getHasReference() != 1)) {
                throw new BusinessException("评估器「" + prompt.getName() + "」为参考对照模式，需要参考答案，但数据集「"
                        + dataset.getName() + "」未标记含参考答案。请更换评估器，或使用含答案的数据集");
            }
        }

        // 校验裁判模型（如果指定了）
        if (dto.getJudgeModelId() != null) {
            EvalModelConfig judgeConfig = modelConfigMapper.selectById(dto.getJudgeModelId());
            if (judgeConfig == null || judgeConfig.getStatus() != 1) throw new BusinessException("裁判模型不存在或已禁用: " + dto.getJudgeModelId());
        }

        EvalTask latest = taskMapper.selectOne(
                new LambdaQueryWrapper<EvalTask>().eq(EvalTask::getName, dto.getName())
                        .orderByDesc(EvalTask::getVersion).last("LIMIT 1"));

        EvalTask task = new EvalTask();
        task.setName(dto.getName());
        task.setVersion(latest != null ? latest.getVersion() + 1 : 1);
        task.setDatasetId(dto.getDatasetId());
        task.setJudgeModelId(dto.getJudgeModelId());
        task.setAnswerSource("api".equals(dto.getAnswerSource()) ? "api" : "dataset");
        task.setPromptTemplate(dto.getPromptTemplate());
        task.setFieldMapping(dto.getFieldMapping());
        task.setStatus(TaskStatus.PENDING);
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        for (Long modelId : dto.getModelConfigIds()) {
            EvalTaskModel tm = new EvalTaskModel();
            tm.setTaskId(task.getId());
            tm.setModelConfigId(modelId);
            taskModelMapper.insert(tm);
        }

        for (Long promptId : dto.getPromptIds()) {
            EvalPrompt prompt = promptMapper.selectById(promptId);
            EvalTaskPrompt tp = new EvalTaskPrompt();
            tp.setTaskId(task.getId());
            tp.setPromptId(promptId);
            // 冻结评估器快照：任务运行时用此版本，不受评估器后续编辑影响
            if (prompt != null) {
                tp.setPromptVersion(prompt.getVersion() != null ? prompt.getVersion() : 1);
                tp.setPromptName(prompt.getName());
                tp.setPromptTemplate(prompt.getPromptTemplate());
                tp.setDimensionsConfig(prompt.getDimensionsConfig());
            }
            taskPromptMapper.insert(tp);
        }

        // Redis Hash 初始化进度：原子字段，支持实时递增查询
        String progressKey = "eval:task:" + task.getId() + ":progress";
        int totalItems = dataset.getTotalCount() * dto.getModelConfigIds().size();
        redisTemplate.opsForHash().putAll(progressKey, Map.of(
                "TOTAL", String.valueOf(totalItems),
                "COMPLETED", "0",
                "FAILED", "0"
        ));
        redisTemplate.expire(progressKey, 7, TimeUnit.DAYS);

        log.info("评测任务创建成功: id={}, name={}, version={}", task.getId(), task.getName(), task.getVersion());
        return task;
    }

    @Override
    public PageResult<EvalTask> list(int page, int size) {
        Page<EvalTask> pageObj = new Page<>(page, size);
        Page<EvalTask> result = taskMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalTask>().orderByDesc(EvalTask::getCreatedAt));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public EvalTask getById(Long id) { return taskMapper.selectById(id); }

    @Override
    public void start(Long id) {
        EvalTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException("评测任务不存在");
        if (!TaskStatus.PENDING.equals(task.getStatus())) throw new BusinessException("任务状态不允许启动: " + task.getStatus());

        // 更新任务状态为运行中
        task.setStatus(TaskStatus.RUNNING);
        taskMapper.updateById(task);

        // ===== 分片调度：将评测任务拆分为多个分片发送到 Kafka =====
        dispatchShards(task);

        log.info("评测任务已通过分片调度启动: id={}, shards={}", id, task.getShardCount());
    }

    /**
     * 将评测任务拆分为多个分片并调度到 Kafka
     * 每个分片最多 SHARD_SIZE 条 case，每条 case = (modelConfigId, datasetItemId)
     */
    private void dispatchShards(EvalTask task) {
        // 查询关联的模型
        List<EvalTaskModel> taskModels = taskModelMapper.selectList(
                new LambdaQueryWrapper<EvalTaskModel>().eq(EvalTaskModel::getTaskId, task.getId()));
        List<Long> modelIds = new ArrayList<>();
        for (EvalTaskModel tm : taskModels) {
            modelIds.add(tm.getModelConfigId());
        }
        if (modelIds.isEmpty()) {
            throw new BusinessException("任务未关联任何模型");
        }

        // 查询数据集条目
        List<EvalDatasetItem> items = datasetItemMapper.selectList(
                new LambdaQueryWrapper<EvalDatasetItem>()
                        .eq(EvalDatasetItem::getDatasetId, task.getDatasetId())
                        .orderByAsc(EvalDatasetItem::getSeqNo));
        if (items.isEmpty()) {
            throw new BusinessException("数据集为空");
        }

        // 构建所有 case：model × item 的笛卡尔积
        List<CaseItem> allCases = new ArrayList<>();
        for (EvalDatasetItem item : items) {
            for (Long modelId : modelIds) {
                allCases.add(new CaseItem(modelId, item.getId()));
            }
        }

        // 拆分为多个分片
        int totalShards = (allCases.size() + SHARD_SIZE - 1) / SHARD_SIZE;
        task.setShardCount(totalShards);
        taskMapper.updateById(task);

        // 逐个创建分片记录并发送 Kafka 消息
        for (int i = 0; i < totalShards; i++) {
            int start = i * SHARD_SIZE;
            int end = Math.min(start + SHARD_SIZE, allCases.size());
            List<CaseItem> shardCases = allCases.subList(start, end);

            // 创建分片记录
            EvalTaskShard shard = new EvalTaskShard();
            shard.setTaskId(task.getId());
            shard.setShardIndex(i);
            shard.setShardSize(shardCases.size());
            shard.setStatus(ShardStatus.PENDING);
            shard.setCompletedCount(0);
            shard.setFailedCount(0);
            shard.setRetryCount(0);
            shard.setShardData(JSONUtil.toJsonStr(shardCases));
            shard.setCreatedAt(LocalDateTime.now());
            shardMapper.insert(shard);

            // 发送 Kafka 消息（同步发送 + 失败补偿）
            ShardMessage msg = new ShardMessage(task.getId(), i, shardCases, shardCases.size());
            try {
                kafkaTemplate.send(KafkaTopic.EVAL_SHARD_EXECUTE, JSONUtil.toJsonStr(msg))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("分片消息发送失败，已标记待补偿: taskId={}, shard={}", task.getId(), i, e);
                shard.setStatus(ShardStatus.FAILED);
                shard.setErrorMessage("Kafka发送失败: " + e.getMessage());
                shardMapper.updateById(shard);
                // Redis 补偿标记
                redisTemplate.opsForValue().set(
                        "eval:task:" + task.getId() + ":shard-" + i + ":pending", "1", 7, TimeUnit.DAYS);
            }
        }
    }

    @Override
    public void cancel(Long id) {
        EvalTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException("评测任务不存在");
        if (TaskStatus.COMPLETED.equals(task.getStatus()) || TaskStatus.CANCELLED.equals(task.getStatus())) {
            throw new BusinessException("任务已结束，无法取消");
        }
        task.setStatus(TaskStatus.CANCELLED);
        taskMapper.updateById(task);
        // 清理分布式锁，让 Worker/Consumer 尽快停止
        redisTemplate.delete("eval:task:" + id + ":exec-lock");
        // 清理所有分片锁
        if (task.getShardCount() != null && task.getShardCount() > 0) {
            for (int i = 0; i < task.getShardCount(); i++) {
                redisTemplate.delete("eval:shard:" + id + ":shard-" + i + ":lock");
            }
        }
        log.info("任务已取消: taskId={}", id);
    }

    @Override
    public int getProgress(Long id) {
        String progressKey = "eval:task:" + id + ":progress";
        Object totalObj = redisTemplate.opsForHash().get(progressKey, "TOTAL");
        if (totalObj == null) {
            // Redis key 不存在（可能过期或未初始化），回退到 MySQL
            EvalTask task = taskMapper.selectById(id);
            if (task == null) return 0;
            if (TaskStatus.COMPLETED.equals(task.getStatus())) return 100;
            if (TaskStatus.PENDING.equals(task.getStatus())) return 0;
            // 分片模式：从分片表计算进度
            if (task.getShardCount() != null && task.getShardCount() > 0) {
                long terminalCount = shardMapper.selectCount(
                        new LambdaQueryWrapper<EvalTaskShard>()
                                .eq(EvalTaskShard::getTaskId, id)
                                .in(EvalTaskShard::getStatus, ShardStatus.COMPLETED, ShardStatus.FAILED));
                return (int) (terminalCount * 100 / task.getShardCount());
            }
            return 0;
        }
        int total = Integer.parseInt(totalObj.toString());
        if (total <= 0) return 0;
        long completed = Long.parseLong(
                String.valueOf(redisTemplate.opsForHash().get(progressKey, "COMPLETED")));
        long failed = Long.parseLong(
                String.valueOf(redisTemplate.opsForHash().get(progressKey, "FAILED")));
        return (int) ((completed + failed) * 100 / total);
    }
}
