package com.eval.model.vo;

import lombok.Data;

/**
 * 人工校验统计 VO（人机 + 人人）
 */
@Data
public class HumanReviewStatsVO {

    /** 已有人工校验的判定数（所有校验人的判定总和） */
    private int reviewedCount;

    /** 已校验的样本数（有≥1个人判定的样本） */
    private int reviewedSampleCount;

    // ===== 人机一致 =====

    /** 人工判定 == AI 判定的数量 */
    private int agreeWithAiCount;

    /** 人机一致率 (%) */
    private double humanAiAgreementRate;

    /** AI 判定为 badcase 的数（供对照） */
    private int aiBadcaseCount;

    // ===== 人人一致 =====

    /** 被 ≥2 个校验人判定的样本数 */
    private int multiReviewerSampleCount;

    /** 多校验人样本中，所有校验人判定一致的样本数 */
    private int allAgreeSampleCount;

    /** 人人一致率 (%) */
    private double humanHumanAgreementRate;

    /** 需要校验的样本总数（该任务所有判定记录） */
    private int totalCount;

    /** 参与校验的人数 */
    private int reviewerCount;

    // ===== Kappa 系数（扣除偶然一致） =====

    /** Fleiss' Kappa：所有校验人在所有多校验人样本上的整体一致性 [-1, 1] */
    private double fleissKappa;

    /** Cohen's Kappa 平均值：每两两校验人之间的 Cohen's Kappa 取平均 [-1, 1] */
    private double cohenKappaAvg;

    /** 两两 Cohen's Kappa 对数（用于判断样本是否充足） */
    private int cohenKappaPairCount;

    /** Kappa 档位描述（如"良好"） */
    private String kappaLevel;
}
