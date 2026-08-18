package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 模型配置请求DTO
 */
@Data
public class ModelConfigDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型名称 */
    @NotBlank(message = "模型名称不能为空")
    private String name;

    /** 提供商 */
    @NotBlank(message = "提供商不能为空")
    private String provider;

    /** API 地址 */
    @NotBlank(message = "API地址不能为空")
    private String apiBase;

    /** API Key */
    @NotBlank(message = "API Key不能为空")
    private String apiKey;

    /** 模型ID */
    @NotBlank(message = "模型ID不能为空")
    private String modelId;

    /** 模型类型: evaluated-被测, judge-裁判, both-两者皆可 */
    private String modelType;

    /** 默认温度 */
    private Double temperature;

    /** 最大输出 Token */
    private Integer maxTokens;
}
