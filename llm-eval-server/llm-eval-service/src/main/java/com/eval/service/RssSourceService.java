package com.eval.service;

import com.eval.model.entity.EvalRssSource;

import java.util.List;

/**
 * RSS 订阅源服务
 */
public interface RssSourceService {

    /**
     * 全部订阅源列表
     */
    List<EvalRssSource> listSources();

    /**
     * 新增订阅源
     */
    EvalRssSource addSource(String sourceName, String feedUrl, String description);

    /**
     * 删除订阅源
     */
    void deleteSource(Long id);

    /**
     * 启用/停用订阅源
     */
    void setEnabled(Long id, boolean enabled);

    /**
     * 拉取指定订阅源（返回新增文章数）
     */
    int fetchOne(Long id);

    /**
     * 拉取全部启用的订阅源（返回各源新增数合计）
     */
    int fetchAll();
}