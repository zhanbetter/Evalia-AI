package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.constant.KafkaTopic;
import com.eval.common.constant.TaskStatus;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.*;
import com.eval.model.dto.EvalTaskDTO;
import com.eval.model.entity.*;
import com.eval.service.EvalTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalTaskServiceImpl implements EvalTaskService {

    private final EvalTaskMapper taskMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalTaskPromptMapper taskPromptMapper;
    private final EvalDatasetMapper datasetMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final EvalPromptMapper promptMapper;
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
            EvalTaskPrompt tp = new EvalTaskPrompt();
            tp.setTaskId(task.getId());
            tp.setPromptId(promptId);
            taskPromptMapper.insert(tp);
        }

        String progressKey = "eval:task:" + task.getId() + ":progress";
        int totalItems = dataset.getTotalCount() * dto.getModelConfigIds().size();
        redisTemplate.opsForValue().set(progressKey, "0/" + totalItems);

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
        task.setStatus(TaskStatus.RUNNING);
        taskMapper.updateById(task);
        kafkaTemplate.send(KafkaTopic.EVAL_TASK_EXECUTE, String.valueOf(id));
        log.info("评测任务已通过Kafka启动: id={}", id);
    }

    @Override
    public void cancel(Long id) {
        EvalTask task = taskMapper.selectById(id);
        if (task == null) throw new BusinessException("评测任务不存在");
        task.setStatus(TaskStatus.FAILED);
        taskMapper.updateById(task);
    }

    @Override
    public int getProgress(Long id) {
        String progressKey = "eval:task:" + id + ":progress";
        String value = redisTemplate.opsForValue().get(progressKey);
        if (StrUtil.isBlank(value)) {
            EvalTask task = taskMapper.selectById(id);
            return (task != null && TaskStatus.COMPLETED.equals(task.getStatus())) ? 100 : 0;
        }
        String[] parts = value.split("/");
        int completed = Integer.parseInt(parts[0]);
        int total = Integer.parseInt(parts[1]);
        return total > 0 ? (completed * 100 / total) : 0;
    }
}
