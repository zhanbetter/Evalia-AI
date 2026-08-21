package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步预分析任务（AI识别成规则 / 润色多维度 / 数据重复检测）
 * 耗时操作改为异步任务模式：提交返回 jobId，后台线程池执行，前端轮询进度
 */
@Data
@TableName("eval_async_job")
public class EvalAsyncJob implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务类型: PARSE_DIMENSIONS/POLISH/DETECT_DUPLICATES */
    private String jobType;

    /** 任务状态: PENDING/RUNNING/COMPLETED/FAILED */
    private String status;

    /** 进度 0-100 */
    private Integer progress;

    /** 进度描述（如 正在润色维度 2/5） */
    private String progressText;

    /** 请求参数JSON */
    private String payload;

    /** 结果JSON */
    private String resultData;

    /** 失败信息 */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}