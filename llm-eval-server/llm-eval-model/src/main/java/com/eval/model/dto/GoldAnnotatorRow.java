package com.eval.model.dto;

import lombok.Data;

/**
 * 聚合查询行：某数据集下，每个标注者的标注统计（按标注者 × 角色 × 判定）
 */
@Data
public class GoldAnnotatorRow {
    /** 标注者 */
    private String annotator;
    /** 标注角色 */
    private String role;
    /** 判定: 1-badcase 0-goodcase */
    private Integer isBadcase;
    /** 该判定条数 */
    private Long cnt;
}