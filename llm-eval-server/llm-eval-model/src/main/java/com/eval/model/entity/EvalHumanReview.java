package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 人工校验记录：用于校准 AI 裁判判定，计算 AI vs 人工一致率
 */
@Data
@TableName("eval_human_review")
public class EvalHumanReview implements Serializable {

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

    /** AI判定: 1-badcase 0-非badcase */
    private Integer isBadcaseAi;

    /** 人工判定: 1-badcase 0-非badcase */
    private Integer isBadcaseHuman;

    /** 人工与AI是否一致: 1-一致 0-不一致 */
    private Integer agree;

    /** 人工备注 */
    private String comment;

    /** 校验人 */
    private String reviewer;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
