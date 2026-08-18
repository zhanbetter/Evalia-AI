package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测结果（模型回复）
 */
@Data
@TableName("eval_result")
public class EvalResult implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务ID */
    private Long taskId;

    /** 模型配置ID */
    private Long modelConfigId;

    /** 数据集条目ID */
    private Long datasetItemId;

    /** 实际发送的 Prompt */
    private String prompt;

    /** 模型回复 */
    private String response;

    /** 响应耗时(ms) */
    private Integer latencyMs;

    /** Token 消耗 */
    private Integer tokenUsage;

    /** 精确匹配得分(0/1) */
    private Integer exactMatchScore;

    /** 关键词匹配得分(0/1) */
    private Integer keywordMatchScore;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
