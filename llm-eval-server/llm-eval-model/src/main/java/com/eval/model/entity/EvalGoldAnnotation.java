package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 金标准标注（条目级，脱离任务/被测模型的独立标注台）
 *
 * 一位标注者对一个数据集条目的独立判定 good/bad；同条目多位标注者投片后
 * 用于一致性统计（覆盖率/一致率/Fleiss Kappa）与多数表决，得出条目级金标准。
 */
@Data
@TableName("eval_gold_annotation")
public class EvalGoldAnnotation implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据集条目ID */
    private Long datasetItemId;

    /** 标注者 */
    private String annotator;

    /** 标注角色: ANNOTATOR-标注员 EXPERT-专家 REVIEWER-复核 */
    private String role;

    /** 标注结论: 1-badcase 0-goodcase */
    private Integer isBadcase;

    /** 标注备注 */
    private String comment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}