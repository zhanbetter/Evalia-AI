package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 任务汇总统计（整体+按维度badcase率）
 */
@Data
@TableName("eval_task_summary")
public class EvalTaskSummary implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务ID */
    private Long taskId;

    /** 被测模型ID */
    private Long modelConfigId;

    /** 评测Prompt ID */
    private Long promptId;

    /** 维度名称(NULL=整体) */
    private String dimension;

    /** 总条目数 */
    private Integer totalCount;

    /** badcase数量 */
    private Integer badcaseCount;

    /** 无法判定数(AI输出无法解析) */
    private Integer skipCount;

    /** badcase率(%) */
    private BigDecimal badcaseRate;

    /** 平均响应耗时(ms) */
    private Integer avgLatencyMs;

    /** 总Token消耗 */
    private Long totalTokenUsage;
}
