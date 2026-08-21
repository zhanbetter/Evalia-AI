package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测任务
 */
@Data
@TableName("eval_task")
public class EvalTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    private String name;

    /** 评测版本号 */
    private Integer version;

    /** 数据集ID */
    private Long datasetId;

    /** 裁判模型ID（NULL=自动选第一个可用模型） */
    private Long judgeModelId;

    /** 回答来源: dataset-数据集已有回答, api-现场调模型生成 */
    private String answerSource;

    /** Prompt 模板（含 {question} 占位符，answer_source=api时用于调用被测模型） */
    private String promptTemplate;

    /** 占位符→数据集字段映射JSON, 如 {"question":"query","context":"人设"} */
    private String fieldMapping;

    /** 任务状态: PENDING/RUNNING/COMPLETED/FAILED */
    private String status;

    /** 完成百分比 0-100 */
    private Integer progress;

    /** 分片总数（大规模评测拆分后写入，小任务为0） */
    private Integer shardCount;

    /** 创建者ID（eval_user.id）；null=历史无归属数据 */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
