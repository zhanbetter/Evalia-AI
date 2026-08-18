package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 多角色人工校验判定：每个校验人对每个样本提交一条独立判定
 */
@Data
@TableName("eval_reviewer_verdict")
public class EvalReviewerVerdict implements Serializable {

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

    /** 校验人 */
    private String reviewer;

    /** 校验人角色: normal-普通评委, expert-专家 */
    private String role;

    /** 该校验人的判定: 1-badcase 0-非badcase */
    private Integer isBadcaseHuman;

    /** 备注 */
    private String comment;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
