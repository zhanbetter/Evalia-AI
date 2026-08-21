package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 金标准标注提交请求
 */
@Data
public class GoldAnnotateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数据集条目ID */
    @NotNull(message = "数据集条目ID不能为空")
    private Long datasetItemId;

    /** 标注者 */
    @NotBlank(message = "标注者不能为空")
    private String annotator;

    /** 标注角色: ANNOTATOR-标注员 EXPERT-专家 REVIEWER-复核（默认 ANNOTATOR） */
    private String role;

    /** 判定: 1-badcase 0-goodcase */
    @NotNull(message = "标注结论不能为空")
    private Integer isBadcase;

    /** 标注备注（可选） */
    private String comment;
}