package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 评测Prompt请求DTO
 */
@Data
public class PromptDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Prompt名称 */
    @NotBlank(message = "Prompt名称不能为空")
    private String name;

    /** 描述 */
    private String description;

    /** 评测Prompt模板（旧模式：手动填写；新模式：由dimensionsConfig自动生成） */
    private String promptTemplate;

    /** 维度配置JSON（结构化定义：维度、Rubric、阈值等） */
    private String dimensionsConfig;

    /** 评测模式: quality-质量评判(无需参考答案), reference-参考对照(需参考答案) */
    private String evaluationMode;
}
