package com.eval.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 数据集重复检测结果
 */
@Data
public class DuplicateDetectionResult {

    /** 重复组列表 */
    private List<DuplicateGroup> groups;

    /** 数据集总条目数 */
    private int totalItems;

    /** 检测到的重复条目总数 */
    private int duplicateCount;

    /** 检测字段 */
    private String fieldName;

    /** 相似度阈值 */
    private double threshold;

    /**
     * 一组重复条目
     */
    @Data
    public static class DuplicateGroup {
        /** 组内条目 */
        private List<GroupItem> items;
        /** 该组最高相似度 */
        private double maxSimilarity;
    }

    /**
     * 重复组内的单条数据
     */
    @Data
    public static class GroupItem {
        private Long id;
        private Integer seqNo;
        private String question;
        private String referenceAnswer;
        private String context;
        private String category;
        /** 与代表条目的相似度，代表条目本身为 1.0 */
        private double similarity;
        /** 该条目被比对字段的实际值 */
        private String fieldValue;
    }
}
