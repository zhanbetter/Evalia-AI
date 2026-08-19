package com.eval.web.controller;

import com.eval.common.result.Result;
import com.eval.model.entity.EvalRssSource;
import com.eval.service.RssSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * RSS 订阅源管理 API
 */
@RestController
@RequestMapping("/api/rss-sources")
@RequiredArgsConstructor
public class RssSourceController {

    private final RssSourceService rssSourceService;

    /**
     * 订阅源列表
     */
    @GetMapping
    public Result<List<EvalRssSource>> list() {
        return Result.success(rssSourceService.listSources());
    }

    /**
     * 新增订阅源
     */
    @PostMapping
    public Result<EvalRssSource> add(@RequestBody Map<String, String> body) {
        EvalRssSource source = rssSourceService.addSource(
                body.get("sourceName"),
                body.get("feedUrl"),
                body.get("description"));
        return Result.success(source);
    }

    /**
     * 删除订阅源
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        rssSourceService.deleteSource(id);
        return Result.success();
    }

    /**
     * 启用/停用订阅源
     */
    @PutMapping("/{id}/enabled")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        rssSourceService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
        return Result.success();
    }

    /**
     * 手动拉取指定订阅源（返回新增文章数）
     */
    @PostMapping("/{id}/fetch")
    public Result<Integer> fetchOne(@PathVariable Long id) {
        return Result.success(rssSourceService.fetchOne(id));
    }

    /**
     * 手动拉取全部启用的订阅源（返回新增总数）
     */
    @PostMapping("/fetch-all")
    public Result<Integer> fetchAll() {
        return Result.success(rssSourceService.fetchAll());
    }
}