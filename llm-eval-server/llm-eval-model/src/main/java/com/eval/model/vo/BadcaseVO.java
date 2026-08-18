package com.eval.model.vo;

import com.eval.model.entity.EvalHumanReview;
import com.eval.model.entity.EvalReviewerVerdict;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Badcase 列表 VO（含原始问题和模型回答）
 */
@Data
public class BadcaseVO {

    private Long id;

    private Long taskId;

    private Long modelConfigId;

    private Long datasetItemId;

    private Long promptId;

    /** 评测维度(NULL=整体判定记录，非NULL=单维度判定记录) */
    private String dimension;

    /** AI判定: 1-badcase 0-非badcase */
    private Integer isBadcase;

    /** AI判定的维度(JSON数组) */
    private String dimensions;

    /** AI判定理由 */
    private String reason;

    /** AI返回的完整解析结果JSON */
    private String parsedResult;

    /** 判定状态 */
    private String judgeStatus;

    /** Prompt Cache 命中 token 数 */
    private Integer cachedTokens;

    private LocalDateTime createdAt;

    // ---- 以下为关联补充字段 ----

    /** 原始问题（来自 eval_dataset_item） */
    private String question;

    /** 参考答案（来自 eval_dataset_item） */
    private String referenceAnswer;

    /** 被测模型回答（来自 eval_result） */
    private String modelResponse;

    /** 数据集条目扩展字段JSON（来自 eval_dataset_item） */
    private String extraFields;

    /** 被测模型名称（来自 eval_model_config） */
    private String modelName;

    /** 评测 Prompt 名称（来自 eval_prompt） */
    private String promptName;

    /** 人工校验记录（旧版单层，兼容） */
    private EvalHumanReview humanReview;

    /** 所有校验人的判定列表（多角色） */
    private List<EvalReviewerVerdict> reviewerVerdicts;

    /** 金标准（专家裁决后的最终结论，如有） */
    private com.eval.model.entity.EvalGoldLabel goldLabel;

    /** 评委是否有分歧（专家裁决视图用） */
    private Boolean hasDisagreement;

    /** AI 判定是否与金标准一致（人机对比视图用） */
    private Boolean aiGoldAgree;
}
