package com.eval.model.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;

/**
 * 创建评测任务请求DTO
 */
@Data
public class EvalTaskDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务名称 */
    @NotBlank(message = "任务名称不能为空")
    private String name;

    /** 数据集ID */
    @NotNull(message = "请选择数据集")
    private Long datasetId;

    /** 裁判模型ID（NULL=自动选第一个可用模型） */
    private Long judgeModelId;

    /** 回答来源: dataset-数据集已有回答, api-现场调模型生成 */
    private String answerSource;

    /** 调用被测模型的Prompt模板（answer_source=api时使用） */
    private String promptTemplate;

    /** 占位符→数据集字段映射JSON, 如 {"question":"query","context":"人设"} */
    private String fieldMapping;

    /** 模型配置ID列表 */
    @NotNull(message = "请选择评测模型")
    private List<Long> modelConfigIds;

    /** 评测Prompt ID列表 */
    @NotNull(message = "请选择评测Prompt")
    private List<Long> promptIds;
}
