package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测Prompt（用户自定义，维度/标准/badcase定义全在这里）
 */
@Data
@TableName("eval_prompt")
public class EvalPrompt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本号（更新时自动+1） */
    private Integer version;

    /** Prompt名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 评测Prompt模板 */
    private String promptTemplate;

    /** 维度配置JSON（结构化定义：维度、Rubric、阈值等） */
    private String dimensionsConfig;

    /** 评测模式: quality-质量评判(无需参考答案), reference-参考对照(需参考答案) */
    private String evaluationMode;

    /** 启用状态: 1-启用 0-禁用 */
    private Integer status;

    /** 创建者ID（eval_user.id）；null=历史无归属数据（仅管理员可删） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
