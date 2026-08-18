package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI评测判定结果
 */
@Data
@TableName("eval_judge_result")
public class EvalJudgeResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务ID */
    private Long taskId;

    /** 被测模型ID */
    private Long modelConfigId;

    /** 数据集条目ID */
    private Long datasetItemId;

    /** 评测Prompt ID */
    private Long promptId;

    /** 评测维度(NULL=整体判定记录，非NULL=单维度判定记录) */
    private String dimension;

    /** AI判定: 1-badcase 0-非badcase */
    private Integer isBadcase;

    /** AI判定的维度(JSON数组) */
    private String dimensions;

    /** AI判定理由 */
    private String reason;

    /** AI返回的完整解析结果JSON（含各维度分数和理由） */
    private String parsedResult;

    /** 判定状态: PENDING/JUDGED/SKIP */
    private String judgeStatus;

    /** Prompt Cache 命中的 token 数 */
    private Integer cachedTokens;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
