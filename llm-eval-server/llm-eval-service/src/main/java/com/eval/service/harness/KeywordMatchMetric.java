package com.eval.service.harness;

import cn.hutool.core.util.StrUtil;

import java.util.Map;

/**
 * 关键词匹配指标：参考答案中的任一关键词出现在被测模型回答中得 1 分，否则 0 分。
 * 0 分不视为 badcase（辅助参考指标）。
 */
public class KeywordMatchMetric implements Metric {

    @Override
    public String name() {
        return "keyword_match";
    }

    @Override
    public MetricResult evaluate(EvalSample sample, Map<String, Object> config) {
        String response = sample.getModelResponse();
        String reference = sample.getReferenceAnswer();
        if (StrUtil.isBlank(response) || StrUtil.isBlank(reference)) {
            return new MetricResult(name(), 0, false, "参考答案或模型回答为空");
        }
        String[] keywords = reference.trim().split("[,，、\\s]+");
        for (String keyword : keywords) {
            if (StrUtil.isNotBlank(keyword) && response.contains(keyword)) {
                return new MetricResult(name(), 1, false, "回答包含关键词「" + keyword + "」");
            }
        }
        return new MetricResult(name(), 0, false, "回答未包含参考答案关键词");
    }
}
