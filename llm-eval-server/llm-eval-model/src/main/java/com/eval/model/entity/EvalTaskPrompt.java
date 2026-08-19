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

    /** 创建任务时评估器版本快照 */
    private Integer promptVersion;

    /** 评估器名称快照 */
    private String promptName;

    /** 评估Prompt模板快照（JudgeWorker优先使用，不受评估器后续编辑影响） */
    private String promptTemplate;

    /** 维度配置JSON快照（含 strict_output） */
    private String dimensionsConfig;
}
