package com.eval.service.harness;

import lombok.Data;

import java.util.Map;

/**
 * 统一评测样本：所有数据集条目归一化为该结构，供指标(Metric)消费。
 * 无论被测模型的回答来自"现场调 API"还是"数据集预存"，最终都落到 modelResponse 字段。
 */
@Data
public class EvalSample {

    /** 数据集条目ID */
    private Long itemId;

    /** 问题/输入 */
    private String question;

    /** 参考答案 */
    private String referenceAnswer;

    /** 上下文/人设 */
    private String context;

    /** 分类标签 */
    private String category;

    /** 被测模型回答（生成阶段产出） */
    private String modelResponse;

    /** 扩展字段（数据集自定义字段，已解析为 Map） */
    private Map<String, String> extraFields;
}
