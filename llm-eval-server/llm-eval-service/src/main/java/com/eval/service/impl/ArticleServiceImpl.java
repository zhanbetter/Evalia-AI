package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.EvalArticleMapper;
import com.eval.model.entity.EvalArticle;
import com.eval.service.ArticleService;
import com.eval.service.RssFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 评测知识文章服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final EvalArticleMapper articleMapper;
    private final RssFetchService rssFetchService;

    @Override
    public PageResult<EvalArticle> listArticles(int page, int size, String keyword, String source) {
        LambdaQueryWrapper<EvalArticle> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) {
            wrapper.and(w -> w
                .like(EvalArticle::getTitle, keyword)
                .or().like(EvalArticle::getSummary, keyword)
                .or().like(EvalArticle::getTags, keyword)
                .or().like(EvalArticle::getAuthor, keyword)
            );
        }
        if (StrUtil.isNotBlank(source)) {
            wrapper.eq(EvalArticle::getSourceName, source);
        }
        wrapper.eq(EvalArticle::getStatus, 1);
        wrapper.orderByDesc(EvalArticle::getPublishedAt);

        Page<EvalArticle> pageResult = articleMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageResult<>(pageResult.getRecords(), pageResult.getTotal(), pageResult.getCurrent(), pageResult.getSize());
    }

    @Override
    public EvalArticle getArticleById(Long id) {
        EvalArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        // 展示前自动清洗站点噪声（历史脏数据无需改库也能干净显示）
        article.setContent(rssFetchService.cleanArticleContent(article.getContent()));
        return article;
    }

    @Override
    public int fetchFromRss(String feedUrl, String sourceName) {
        if (StrUtil.isBlank(feedUrl)) {
            throw new BusinessException("RSS 地址不能为空");
        }
        return rssFetchService.fetchAndStore(feedUrl, sourceName);
    }

    @Override
    public boolean importArticle(String url, String sourceName) {
        return rssFetchService.importArticle(url, sourceName);
    }

    @Override
    public String generateSummary(Long articleId, Long modelConfigId) {
        return rssFetchService.summarizeArticle(articleId, modelConfigId);
    }

    @Override
    public void deleteArticle(Long id) {
        articleMapper.deleteById(id);
    }

    @Override
    public String repairArticle(Long id) {
        EvalArticle article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        String cleaned = rssFetchService.cleanArticleContent(article.getContent());
        if (cleaned != null && !cleaned.equals(article.getContent())) {
            article.setContent(cleaned);
            articleMapper.updateById(article);
        }
        return cleaned;
    }

    @Override
    public int repairAll() {
        int changed = 0;
        for (EvalArticle article : articleMapper.selectList(null)) {
            String cleaned = rssFetchService.cleanArticleContent(article.getContent());
            if (cleaned != null && !cleaned.equals(article.getContent())) {
                article.setContent(cleaned);
                articleMapper.updateById(article);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("批量清理文章噪声完成，共清理 {} 篇", changed);
        }
        return changed;
    }
}
