package com.eval.web.controller;

import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.entity.EvalArticle;
import com.eval.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 评测知识文章 API
 */
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    /**
     * 分页查询文章列表
     */
    @GetMapping
    public Result<PageResult<EvalArticle>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source) {
        return Result.success(articleService.listArticles(page, size, keyword, source));
    }

    /**
     * 获取文章详情
     */
    @GetMapping("/{id}")
    public Result<EvalArticle> getById(@PathVariable Long id) {
        return Result.success(articleService.getArticleById(id));
    }

    /**
     * 手动触发 RSS 拉取
     */
    @PostMapping("/fetch")
    public Result<Integer> fetchRss(@RequestParam String feedUrl,
                                    @RequestParam(required = false) String sourceName) {
        int count = articleService.fetchFromRss(feedUrl, sourceName);
        return Result.success(count);
    }

    /**
     * 单篇文章导入（大厂团队无 RSS 时的替代方案）
     */
    @PostMapping("/import")
    public Result<Boolean> importArticle(@RequestParam String url,
                                         @RequestParam(required = false) String sourceName) {
        boolean imported = articleService.importArticle(url, sourceName);
        return Result.success(imported);
    }

    /**
     * 为文章生成 AI 摘要
     */
    @PostMapping("/{id}/summary")
    public Result<String> summarize(@PathVariable Long id,
                                    @RequestParam(required = false) Long modelConfigId) {
        String summary = articleService.generateSummary(id, modelConfigId);
        return Result.success(summary);
    }

    /**
     * 删除文章
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        articleService.deleteArticle(id);
        return Result.success();
    }

    /**
     * 清理单篇文章噪声并写回入库
     */
    @PostMapping("/{id}/repair")
    public Result<String> repair(@PathVariable Long id) {
        return Result.success(articleService.repairArticle(id));
    }

    /**
     * 批量清理全部文章噪声（返回被清理篇数）
     */
    @PostMapping("/repair-all")
    public Result<Integer> repairAll() {
        return Result.success(articleService.repairAll());
    }
}
