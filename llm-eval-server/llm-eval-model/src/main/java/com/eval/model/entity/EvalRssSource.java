package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RSS 订阅源
 */
@Data
@TableName("eval_rss_source")
public class EvalRssSource implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源名称 */
    private String sourceName;

    /** RSS/Atom 地址（tencent 类型时为腾讯云 JSON 接口地址） */
    private String feedUrl;

    /** 源类型：rss-标准 feed（默认），tencent-腾讯云开发者社区 JSON 接口 */
    private String sourceType;

    /** 描述 */
    private String description;

    /** 1-启用 0-停用 */
    private Integer status;

    /** 上次拉取时间 */
    private LocalDateTime lastFetchedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}