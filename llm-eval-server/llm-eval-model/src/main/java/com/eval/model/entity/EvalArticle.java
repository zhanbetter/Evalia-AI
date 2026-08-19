package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测知识文章（RSS 拉取 + AI 摘要）
 */
@Data
@TableName("eval_article")
public class EvalArticle implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文章标题 */
    private String title;

    /** 来源团队/博客名 */
    private String sourceName;

    /** 原文链接（去重用） */
    private String sourceUrl;

    /** 作者 */
    private String author;

    /** 标签，逗号分隔 */
    private String tags;

    /** AI 生成摘要 */
    private String summary;

    /** 文章正文（HTML 或纯文本） */
    private String content;

    /** 原文发布时间 */
    private LocalDateTime publishedAt;

    /** 入库时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime fetchedAt;

    /** 状态 1-正常 0-隐藏 */
    private Integer status;
}
