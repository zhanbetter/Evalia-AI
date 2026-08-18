package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置
 */
@Data
@TableName("eval_model_config")
public class EvalModelConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模型名称（如 DeepSeek-V3） */
    private String name;

    /** 提供商（openai/deepseek/zhipu 等） */
    private String provider;

    /** API 地址 */
    private String apiBase;

    /** API Key */
    private String apiKey;

    /** 模型ID（如 deepseek-chat） */
    private String modelId;

    /** 模型类型: evaluated-被测, judge-裁判, both-两者皆可 */
    private String modelType;

    /** 默认温度 */
    private BigDecimal temperature;

    /** 最大输出 Token */
    private Integer maxTokens;

    /** 启用状态: 1-启用 0-禁用 */
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
