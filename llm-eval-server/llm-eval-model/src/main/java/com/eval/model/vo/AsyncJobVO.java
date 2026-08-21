package com.eval.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步任务状态视图（轮询用，不含内部 payload / 大体积结果）
 */
@Data
public class AsyncJobVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 任务类型: PARSE_DIMENSIONS/POLISH/DETECT_DUPLICATES */
    private String jobType;

    /** PENDING/RUNNING/COMPLETED/FAILED */
    private String status;

    /** 进度 0-100 */
    private Integer progress;

    /** 进度描述（如 正在润色维度 2/5：准确性） */
    private String progressText;

    /** 失败信息（仅 FAILED 时有值） */
    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}