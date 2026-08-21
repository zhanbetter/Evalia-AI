package com.eval.common.constant;

/**
 * 异步预分析任务类型
 * 上游耗时操作（AI识别/润色/重复检测）通过 eval_async_job 表异步执行
 */
public class AsyncJobType {

    /** AI 识别为结构化规则：单次 LLM 调用 */
    public static final String PARSE_DIMENSIONS = "PARSE_DIMENSIONS";

    /** 润色多维度：并行为每个维度调用 LLM 优化 rubric */
    public static final String POLISH = "POLISH";

    /** 数据集重复检测：内存相似度计算 */
    public static final String DETECT_DUPLICATES = "DETECT_DUPLICATES";
}