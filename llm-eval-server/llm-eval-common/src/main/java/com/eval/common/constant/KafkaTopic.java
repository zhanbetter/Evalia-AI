package com.eval.common.constant;

/**
 * Kafka Topic 常量
 */
public class KafkaTopic {

    /** 评测执行（旧：整个任务一条消息，废弃中） */
    public static final String EVAL_TASK_EXECUTE = "eval-task-execute";

    /** 分片评测执行（新：每个分片一条消息，10W+ 规模） */
    public static final String EVAL_SHARD_EXECUTE = "eval-shard-execute";

    /** LLM-as-Judge 评分 */
    public static final String EVAL_TASK_JUDGE = "eval-task-judge";
}
