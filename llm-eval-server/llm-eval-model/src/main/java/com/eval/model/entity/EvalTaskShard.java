package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测任务分片
 * 大规模数据集拆分为多个分片，每个分片独立消费、独立容错
 */
@Data
@TableName("eval_task_shard")
public class EvalTaskShard implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务ID */
    private Long taskId;

    /** 分片序号（从0开始） */
    private Integer shardIndex;

    /** 分片大小（该分片包含的 case 数） */
    private Integer shardSize;

    /** 分片状态: PENDING/RUNNING/COMPLETED/FAILED */
    private String status;

    /** 已完成 case 数（含成功+失败） */
    private Integer completedCount;

    /** 失败 case 数 */
    private Integer failedCount;

    /** 重试次数 */
    private Integer retryCount;

    /** 分片数据 JSON（序列化的 CaseItem 列表） */
    private String shardData;

    /** 失败原因 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
