package com.eval.service.harness;

import java.util.Map;

/**
 * 评测指标接口：每个评测标准是一个独立的 Metric 实现，可插拔。
 * 实现类负责：对单个样本打分，返回是否 badcase 及理由。
 * 新增评测标准 = 新增一个 Metric 实现，无需改动执行引擎。
 */
public interface Metric {

    /**
     * 指标名称（用于汇总统计的维度名）
     */
    String name();

    /**
     * 评测单个样本
     * @param sample 统一评测样本
     * @param config 指标配置（如维度定义、阈值、裁判模型等）
     */
    MetricResult evaluate(EvalSample sample, Map<String, Object> config);
}
