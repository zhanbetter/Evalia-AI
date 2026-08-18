package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 数据集条目DTO
 */
@Data
public class DatasetItemDTO {

    @NotBlank(message = "问题不能为空")
    private String question;

    private String referenceAnswer;

    private String context;

    private String category;

    /** 扩展字段JSON */
    private String extraFields;
}
