package com.eval.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 金标准标注一致性统计
 */
@Data
public class GoldAnnotationStatsVO {

    /** 数据集总条目数 */
    private Long totalItems;

    /** 已被至少一人标注的条目数 */
    private Long annotatedItems;

    /** 标注记录总数 */
    private Long annotationCount;

    /** 标注覆盖率 0-1 */
    private Double coverageRate;

    /** 有 ≥2 人标注的条目数 */
    private Long multiAnnotatedItems;

    /** 有 ≥2 人标注且判定一致的条目数 */
    private Long agreedItems;

    /** 一致率（多标注条目中全票一致的比例）0-1，无多标注条目时为 null */
    private Double agreementRate;

    /** Fleiss Kappa（多标注者一致性），无多标注条目时为 null */
    private Double fleissKappa;

    /** 各标注者统计（按标注总数降序） */
    private List<AnnotatorStat> annotatorStats = new ArrayList<>();

    @Data
    public static class AnnotatorStat {
        private String annotator;
        private String role;
        private Integer goodCount;
        private Integer badCount;
        private Integer totalCount;
    }
}