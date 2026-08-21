package com.eval.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.eval.common.constant.AsyncJobType;
import com.eval.common.constant.TaskStatus;
import com.eval.common.exception.BusinessException;
import com.eval.dao.mapper.EvalAsyncJobMapper;
import com.eval.model.dto.DuplicateDetectionResult;
import com.eval.model.entity.EvalAsyncJob;
import com.eval.model.vo.AsyncJobVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步预分析任务服务
 *
 * 耗时操作（AI识别成规则 / 润色多维度 / 数据重复检测）从同步 HTTP 改为异步任务模式：
 *   1. submit 返回 jobId（HTTP 立即响应，不再被 Long 任务阻塞）
 *   2. 后台线程池执行 worker
 *   3. 前端通过 GET /api/async-jobs/{id} 轮询进度，完成后再取结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncJobService {

    private final EvalAsyncJobMapper jobMapper;
    private final PromptService promptService;
    private final DuplicateDetectionService duplicateDetectionService;

    /** 任务执行线程池（4 并发，避免大任务挤占 HTTP 线程） */
    private final ExecutorService jobExecutor = Executors.newFixedThreadPool(4);

    // ======================== 对外接口 ========================

    /** 提交任务，立即返回（后台执行） */
    public EvalAsyncJob submit(String type, String payload) {
        if (!AsyncJobType.PARSE_DIMENSIONS.equals(type)
                && !AsyncJobType.POLISH.equals(type)
                && !AsyncJobType.DETECT_DUPLICATES.equals(type)) {
            throw new BusinessException("未知的任务类型: " + type);
        }
        if (StrUtil.isBlank(payload)) {
            throw new BusinessException("任务参数不能为空");
        }
        EvalAsyncJob job = new EvalAsyncJob();
        job.setJobType(type);
        job.setStatus(TaskStatus.PENDING);
        job.setProgress(0);
        job.setProgressText("");
        job.setPayload(payload);
        job.setCreatedAt(LocalDateTime.now());
        jobMapper.insert(job);

        final Long jobId = job.getId();
        jobExecutor.submit(() -> run(jobId));
        log.info("已提交异步任务: id={}, type={}", jobId, type);
        return job;
    }

    /** 获取任务实体（内部使用） */
    public EvalAsyncJob getJob(Long id) {
        EvalAsyncJob job = jobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException("任务不存在: id=" + id);
        }
        return job;
    }

    /** 获取任务状态（轮询用，屏蔽内部 payload/结果字段） */
    public AsyncJobVO getJobVO(Long id) {
        return toVO(getJob(id));
    }

    /** 实体转 VO（供轮询接口返回，避免把大体积结果/请求参数暴露给前端） */
    public AsyncJobVO toVO(EvalAsyncJob job) {
        AsyncJobVO vo = new AsyncJobVO();
        vo.setId(job.getId());
        vo.setJobType(job.getJobType());
        vo.setStatus(job.getStatus());
        vo.setProgress(job.getProgress());
        vo.setProgressText(job.getProgressText());
        vo.setErrorMessage(job.getErrorMessage());
        vo.setCreatedAt(job.getCreatedAt());
        vo.setStartedAt(job.getStartedAt());
        vo.setFinishedAt(job.getFinishedAt());
        return vo;
    }

    /** 获取任务结果（仅 COMPLETED 后可用） */
    public Object getResult(Long id) {
        EvalAsyncJob job = getJob(id);
        if (!TaskStatus.COMPLETED.equals(job.getStatus())) {
            throw new BusinessException("任务尚未完成，请稍后再试");
        }
        if (StrUtil.isBlank(job.getResultData())) {
            return null;
        }
        return JSONUtil.parse(job.getResultData());
    }

    // ======================== 后台执行 ========================

    private void run(Long jobId) {
        EvalAsyncJob job = jobMapper.selectById(jobId);
        if (job == null) {
            log.warn("异步任务不存在: jobId={}", jobId);
            return;
        }
        try {
            job.setStatus(TaskStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            jobMapper.updateById(job);

            JSONObject payload = JSONUtil.parseObj(job.getPayload());
            String result;
            switch (job.getJobType()) {
                case AsyncJobType.PARSE_DIMENSIONS -> {
                    updateProgress(job, 10, "AI 正在识别评测标准...");
                    result = promptService.parseToDimensions(
                            payload.getLong("modelId"), payload.getStr("text"));
                    updateProgress(job, 90, "识别完成，正在整理结果...");
                }
                case AsyncJobType.POLISH -> {
                    updateProgress(job, 10, "准备润色...");
                    result = promptService.polishParallel(
                            payload.getLong("modelId"),
                            payload.getStr("dimensionsConfig"),
                            msg -> {
                                int p = 20;
                                try {
                                    String[] parts = msg.split("/");
                                    int done = Integer.parseInt(parts[0].trim());
                                    int total = Integer.parseInt(parts[1].split("：")[0].trim());
                                    p = 20 + (int) Math.round(70.0 * done / total);
                                } catch (Exception ignored) {
                                    // 解析失败则保持当前进度
                                }
                                updateProgress(job, p, "正在" + msg);
                            });
                    updateProgress(job, 100, "润色完成");
                }
                case AsyncJobType.DETECT_DUPLICATES -> {
                    updateProgress(job, 5, "正在加载数据集...");
                    DuplicateDetectionResult detectResult = duplicateDetectionService.detect(
                            payload.getLong("datasetId"),
                            payload.getStr("fieldName"),
                            payload.getDouble("threshold", 0.8),
                            (p, phase) -> updateProgress(job, p, phase));
                    result = JSONUtil.toJsonStr(detectResult);
                    updateProgress(job, 100, "检测完成");
                }
                default -> throw new BusinessException("未实现的任务类型: " + job.getJobType());
            }

            job.setResultData(result);
            job.setStatus(TaskStatus.COMPLETED);
            log.info("异步任务完成: id={}, type={}", jobId, job.getJobType());
        } catch (Exception e) {
            log.error("异步任务执行失败: id={}, type={}", jobId, job.getJobType(), e);
            job.setStatus(TaskStatus.FAILED);
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        }
    }

    /** 更新进度（并发业务线程回调，需同步防止丢写） */
    private synchronized void updateProgress(EvalAsyncJob job, int progress, String text) {
        job.setProgress(progress);
        job.setProgressText(text);
        jobMapper.updateById(job);
    }
}