package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 数据集字段定义(Schema)
 */
@Data
@TableName("eval_dataset_schema")
public class EvalDatasetSchema implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联数据集ID */
    private Long datasetId;

    /** 原始字段名(文件列名) */
    private String fieldName;

    /** 显示名称 */
    private String displayName;

    /** 字段类型: TEXT/NUMBER/ENUM/TAGS */
    private String fieldType;

    /** 字段含义描述 */
    private String description;

    /** 角色: QUESTION/REFERENCE/CONTEXT/CATEGORY/CUSTOM */
    private String role;

    /** 是否必填 */
    private Integer required;

    /** 排序 */
    private Integer sortOrder;
}
