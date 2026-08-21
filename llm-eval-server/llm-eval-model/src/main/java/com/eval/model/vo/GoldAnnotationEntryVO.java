package com.eval.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个标注记录（展示用）
 */
@Data
public class GoldAnnotationEntryVO {

    private Long id;

    /** 标注者 */
    private String annotator;

    /** 标注角色 */
    private String role;

    /** 判定: 1-badcase 0-goodcase */
    private Integer isBadcase;

    /** 标注备注 */
    private String comment;

    private LocalDateTime createdAt;
}