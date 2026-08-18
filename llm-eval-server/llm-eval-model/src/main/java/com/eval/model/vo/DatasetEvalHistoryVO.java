package com.eval.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据集评测历史 VO：一个数据集被哪些任务测过，每个任务的结果
 */
@Data
public class DatasetEvalHistoryVO {

    /** 任务ID */
    private Long taskId;

    /** 任务名 */
    private String taskName;

    /** 任务版本 */
    private Integer taskVersion;

    /** 任务状态: PENDING/RUNNING/COMPLETED/FAILED */
    private String status;

    /** 被测模型名 */
    private String modelName;

    /** 评估器名（prompt 名） */
    private String promptName;

    /** 总样本数 */
    private Integer totalCount;

    /** badcase 数 */
    private Integer badcaseCount;

    /** badcase 率 (%) */
    private Double badcaseRate;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
