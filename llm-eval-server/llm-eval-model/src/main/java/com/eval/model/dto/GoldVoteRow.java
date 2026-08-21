package com.eval.model.dto;

import lombok.Data;

/**
 * 聚合查询行：某数据集下，每个条目 × 每种判定（good/bad）的标注数
 */
@Data
public class GoldVoteRow {
    /** 数据集条目ID */
    private Long datasetItemId;
    /** 判定: 1-badcase 0-goodcase */
    private Integer isBadcase;
    /** 该判定票数 */
    private Long cnt;
}