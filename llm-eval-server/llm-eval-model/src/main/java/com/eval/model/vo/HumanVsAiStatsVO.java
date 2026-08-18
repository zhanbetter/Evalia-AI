package com.eval.model.vo;

import lombok.Data;

/**
 * 人机对比统计 VO：AI 判定 vs 金标准（真值）
 */
@Data
public class HumanVsAiStatsVO {

    /** 有金标准的样本数（已裁决的） */
    private int goldCount;

    /** AI 判定与金标准一致数 */
    private int agreeCount;

    /** AI 判定与金标准不一致数 */
    private int disagreeCount;

    /** 不一致率 (%) */
    private double disagreementRate;

    /** 一致率 (%)，与不一致率互补 */
    private double agreementRate;

    // ===== 细分：AI 错判方向 =====

    /** AI 判 badcase 但金标准是 goodcase（AI 过严，假阳性） */
    private int aiFalsePositive;

    /** AI 判 goodcase 但金标准是 badcase（AI 漏判，假阴性） */
    private int aiFalseNegative;
}
