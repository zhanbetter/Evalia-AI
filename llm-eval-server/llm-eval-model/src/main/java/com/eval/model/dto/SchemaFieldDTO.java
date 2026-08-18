package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 数据集Schema字段DTO
 */
@Data
public class SchemaFieldDTO {

    @NotBlank(message = "字段名不能为空")
    private String fieldName;

    private String displayName;

    /** TEXT/NUMBER/ENUM/TAGS */
    private String fieldType;

    private String description;

    /** QUESTION/REFERENCE/CONTEXT/CATEGORY/CUSTOM */
    @NotBlank(message = "角色不能为空")
    private String role;

    private Integer required;

    private Integer sortOrder;
}
