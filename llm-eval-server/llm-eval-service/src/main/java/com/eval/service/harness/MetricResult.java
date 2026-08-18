package com.eval.service.harness;

import lombok.Data;

/**
 * 指标评测结果：一个样本在一个指标上的打分结果
 */
@Data
public class MetricResult {

    /** 指标名称 */
    private String metricName;

    /** 分数/等级（Integer / String / Boolean） */
    private Object score;

    /** 是否判定为 badcase */
    private boolean badcase;

    /** 判定理由 */
    private String reason;

    public MetricResult() {
    }

    public MetricResult(String metricName, Object score, boolean badcase, String reason) {
        this.metricName = metricName;
        this.score = score;
        this.badcase = badcase;
        this.reason = reason;
    }
}
