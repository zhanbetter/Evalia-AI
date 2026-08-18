package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 专家裁决请求：对有分歧的样本做最终判定，产出金标准
 */
@Data
public class AdjudicationDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "任务ID不能为空")
    private Long taskId;

    @NotNull(message = "被测模型ID不能为空")
    private Long modelConfigId;

    @NotNull(message = "评测Prompt ID不能为空")
    private Long promptId;

    @NotNull(message = "数据集条目ID不能为空")
    private Long datasetItemId;

    /** 金标准判定: 1-badcase 0-非badcase */
    @NotNull(message = "裁决判定不能为空")
    private Integer isBadcase;

    /** 裁决专家 */
    @NotNull(message = "裁决专家不能为空")
    private String adjudicator;

    /** 裁决备注（可选） */
    private String comment;
}
