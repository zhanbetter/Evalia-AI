package com.eval.service.harness;

import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * 精确匹配指标：被测模型回答与参考答案完全一致（忽略大小写）得 1 分，否则 0 分。
 * 0 分不视为 badcase（精确匹配是辅助参考指标，非质量判定）。
 */
public class ExactMatchMetric implements Metric {

    @Override
    public String name() {
        return "exact_match";
    }

    @Override
    public MetricResult evaluate(EvalSample sample, Map<String, Object> config) {
        String response = sample.getModelResponse();
        String reference = sample.getReferenceAnswer();
        boolean matched = StrUtil.isNotBlank(response) && StrUtil.isNotBlank(reference)
                && response.trim().equalsIgnoreCase(reference.trim());
        return new MetricResult(name(), matched ? 1 : 0, false, matched ? "与参考答案完全一致" : "与参考答案不一致");
    }
}
