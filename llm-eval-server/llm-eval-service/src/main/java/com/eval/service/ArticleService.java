package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.entity.EvalArticle;

/**
 * 评测知识文章服务
 */
public interface ArticleService {

    /**
     * 分页查询文章列表
     */
    PageResult<EvalArticle> listArticles(int page, int size, String keyword, String source);

    /**
     * 根据 ID 获取文章
     */
    EvalArticle getArticleById(Long id);

    /**
     * 触发 RSS 拉取（手动调用）
     */
    int fetchFromRss(String feedUrl, String sourceName);

    /**
     * 单篇文章导入（大厂团队无 RSS 时的替代方案）
     */
    boolean importArticle(String url, String sourceName);

    /**
     * 为文章生成 AI 摘要（手动一键总结）
     */
    String generateSummary(Long articleId, Long modelConfigId);

    /**
     * 删除文章
     */
    void deleteArticle(Long id);

    /**
     * 清理单篇文章正文的站点噪声并写回入库，返回清理后的正文
     */
    String repairArticle(Long id);

    /**
     * 批量清理全部文章正文噪声，返回被修改的文章数
     */
    int repairAll();
}
