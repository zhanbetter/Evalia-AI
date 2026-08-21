package com.eval.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 数据集金标准标注条目（分页行）
 */
@Data
public class GoldAnnotationItemVO {

    private Long id;

    /** 序号 */
    private Integer seqNo;

    /** 问题 */
    private String question;

    /** 参考答案 */
    private String referenceAnswer;

    /** 上下文/人设 */
    private String context;

    /** 分类 */
    private String category;

    /** goodcase 票数 */
    private Integer goodCount;

    /** badcase 票数 */
    private Integer badCount;

    /** 已标注总人数 */
    private Integer annotationCount;

    /** 多数表决结论: 1-badcase 0-goodcase null-无有效票或平票 */
    private Integer verdict;

    /** 是否有分歧（存在 ≥2 张票且不一致） */
    private Boolean hasDisagreement;

    /** 扩展字段JSON（自定义列如 model_response 原始值，供标注左侧完整展示样本） */
    private String extraFields;

    /** 标注记录列表（按时间升序） */
    private List<GoldAnnotationEntryVO> annotations;
}