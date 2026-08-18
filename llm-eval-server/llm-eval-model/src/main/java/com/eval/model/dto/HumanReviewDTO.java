package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 人工校验提交请求
 */
@Data
public class HumanReviewDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull(message = "任务ID不能为空")
    private Long taskId;

    @NotNull(message = "被测模型ID不能为空")
    private Long modelConfigId;

    @NotNull(message = "评测Prompt ID不能为空")
    private Long promptId;

    @NotNull(message = "数据集条目ID不能为空")
    private Long datasetItemId;

    /** 人工判定: 1-badcase 0-非badcase */
    @NotNull(message = "人工判定不能为空")
    private Integer isBadcaseHuman;

    /** 人工备注（可选） */
    private String comment;

    /** 校验人（可选） */
    private String reviewer;

    /** 校验人角色: normal-普通评委, expert-专家（默认 normal） */
    private String role;
}
