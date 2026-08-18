package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 数据集条目
 */
@Data
@TableName("eval_dataset_item")
public class EvalDatasetItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联数据集ID */
    private Long datasetId;

    /** 问题/输入 */
    private String question;

    /** 参考答案 */
    private String referenceAnswer;

    /** 上下文/人设 */
    private String context;

    /** 分类标签 */
    private String category;

    /** 扩展字段JSON */
    private String extraFields;

    /** 序号 */
    private Integer seqNo;
}
