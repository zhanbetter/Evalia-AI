package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;

/**
 * 任务-评测Prompt关联
 */
@Data
@TableName("eval_task_prompt")
public class EvalTaskPrompt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 评测任务ID */
    private Long taskId;

    /** 评测Prompt ID */
    private Long promptId;
}
