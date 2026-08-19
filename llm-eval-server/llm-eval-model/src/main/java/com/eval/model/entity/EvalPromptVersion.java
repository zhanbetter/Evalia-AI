package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评估器版本快照：每次更新评估器时，旧版本完整落一份到此表，支持回溯与审计
 */
@Data
@TableName("eval_prompt_version")
public class EvalPromptVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联评估器ID */
    private Long promptId;

    /** 版本号 */
    private Integer version;

    /** Prompt名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 评测Prompt模板 */
    private String promptTemplate;

    /** 维度配置JSON（含 strict_output） */
    private String dimensionsConfig;

    /** 评测模式: quality/reference */
    private String evaluationMode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}