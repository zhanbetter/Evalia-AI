package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 金标准：专家裁决后的最终结论，用作"真值"校准 AI 裁判
 */
@Data
@TableName("eval_gold_label")
public class EvalGoldLabel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务ID */
    private Long taskId;

    /** 被测模型ID */
    private Long modelConfigId;

    /** 评测Prompt ID */
    private Long promptId;

    /** 数据集条目ID */
    private Long datasetItemId;

    /** 金标准判定: 1-badcase 0-非badcase */
    private Integer isBadcase;

    /** 评委是否有分歧: 1-有 0-无(一致) */
    private Integer hasDisagreement;

    /** 裁决专家 */
    private String adjudicator;

    /** 裁决备注 */
    private String adjudicateComment;

    /** 状态: PENDING-待裁决(有分歧), CONFIRMED-已确认 */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
