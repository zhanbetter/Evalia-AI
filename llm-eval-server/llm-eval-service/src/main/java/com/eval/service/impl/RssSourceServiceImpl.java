package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.exception.BusinessException;
import com.eval.dao.mapper.EvalRssSourceMapper;
import com.eval.model.entity.EvalRssSource;
import com.eval.service.RssFetchService;
import com.eval.service.RssSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RSS 订阅源服务实现
 * 提供源的增删改/启停 + 手动/定时全量拉取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RssSourceServiceImpl implements RssSourceService {

    private final EvalRssSourceMapper rssSourceMapper;
    private final RssFetchService rssFetchService;

    /** 每日自动拉取 cron，可通过配置覆盖 eval.rss.cron */
    @Value("${eval.rss.cron:0 30 8 * * *}")
    private String fetchCron;

    @Override
    public List<EvalRssSource> listSources() {
        LambdaQueryWrapper<EvalRssSource> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(EvalRssSource::getStatus);
        wrapper.orderByAsc(EvalRssSource::getId);
        return rssSourceMapper.selectList(wrapper);
    }

    @Override
    public EvalRssSource addSource(String sourceName, String feedUrl, String description) {
        if (StrUtil.isBlank(sourceName)) {
            throw new BusinessException("来源名称不能为空");
        }
        if (StrUtil.isBlank(feedUrl) || !feedUrl.startsWith("http")) {
            throw new BusinessException("订阅地址需为 http(s) 开头的有效 URL");
        }
        // 防重复
        LambdaQueryWrapper<EvalRssSource> w = new LambdaQueryWrapper<>();
        w.eq(EvalRssSource::getFeedUrl, feedUrl.trim());
        if (rssSourceMapper.selectCount(w) > 0) {
            throw new BusinessException("该订阅地址已存在");
        }

        EvalRssSource source = new EvalRssSource();
        source.setSourceName(sourceName.trim());
        source.setFeedUrl(feedUrl.trim());
        source.setSourceType(feedUrl.contains("developer/api/home/article-list") ? "tencent" : "rss");
        source.setDescription(StrUtil.nullToEmpty(description).trim());
        source.setStatus(1);
        rssSourceMapper.insert(source);
        return source;
    }

    @Override
    public void deleteSource(Long id) {
        EvalRssSource source = rssSourceMapper.selectById(id);
        if (source == null) {
            throw new BusinessException("订阅源不存在");
        }
        rssSourceMapper.deleteById(id);
    }

    @Override
    public void setEnabled(Long id, boolean enabled) {
        EvalRssSource source = rssSourceMapper.selectById(id);
        if (source == null) {
            throw new BusinessException("订阅源不存在");
        }
        source.setStatus(enabled ? 1 : 0);
        rssSourceMapper.updateById(source);
    }

    @Override
    public int fetchOne(Long id) {
        EvalRssSource source = rssSourceMapper.selectById(id);
        if (source == null) {
            throw new BusinessException("订阅源不存在");
        }
        return doFetch(source);
    }

    @Override
    public int fetchAll() {
        List<EvalRssSource> sources = rssSourceMapper.selectList(
                new LambdaQueryWrapper<EvalRssSource>().eq(EvalRssSource::getStatus, 1));
        int total = 0;
        for (EvalRssSource source : sources) {
            total += doFetch(source);
        }
        log.info("RSS 全量拉取完成，共 {} 个源，新增 {} 条", sources.size(), total);
        return total;
    }

    /**
     * 拉取单个源并更新 last_fetched_at（即使拉取 0 条也刷新时间）
     */
    private int doFetch(EvalRssSource source) {
        // 默认先按名称拉取，RssFetchService 在 feed 中没找到标题时会用传入名
        int count;
        try {
            if ("tencent".equalsIgnoreCase(source.getSourceType())) {
                count = rssFetchService.fetchTencentColumn(source);
            } else {
                count = rssFetchService.fetchAndStore(source.getFeedUrl(), source.getSourceName());
            }
        } catch (Exception e) {
            log.error("订阅源拉取异常: {} - {}", source.getFeedUrl(), e.getMessage());
            count = 0;
        } finally {
            source.setLastFetchedAt(LocalDateTime.now());
            rssSourceMapper.updateById(source);
        }
        return count;
    }

    /**
     * 每日定时自动拉取所有启用的源
     */
    @Scheduled(cron = "${eval.rss.cron:0 30 8 * * *}")
    public void scheduledFetch() {
        log.info("RSS 定时任务触发，配置 cron={}", fetchCron);
        fetchAll();
    }
}