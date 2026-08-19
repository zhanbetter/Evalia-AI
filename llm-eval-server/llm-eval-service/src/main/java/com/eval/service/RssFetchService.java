package com.eval.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.exception.BusinessException;
import com.eval.dao.mapper.EvalArticleMapper;
import com.eval.dao.mapper.EvalModelConfigMapper;
import com.eval.model.entity.EvalArticle;
import com.eval.model.entity.EvalModelConfig;
import com.eval.model.entity.EvalRssSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * RSS 拉取 + AI 摘要服务
 * 用 OkHttp 获取 XML，javax.xml 解析，去重后入库
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RssFetchService {

    private final EvalArticleMapper articleMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /** 抓取网页用的浏览器 UA（部分站点拦截非浏览器 UA，如 tech.meituan.com 根页面） */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 内容相关性白名单：只收录 AI / Agent / 评测相关文章 */
    private static final java.util.regex.Pattern[] RELEVANT_PATTERNS = {
            java.util.regex.Pattern.compile("\\bagents?\\b|\\bagentic\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bevaluation\\b|\\bevaluations?\\b|\\bevals?\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bllm(s)?\\b|large language model", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bgpt\\b|\\bclaude\\b|\\bdeepseek\\b|\\bqwen\\b|\\bgemini\\b|\\bllama\\b|\\bqwen\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bai\\b|artificial intelligence", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bbenchmark", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\brubric\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bharness\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bjudg(e|ing|es|ement)?s?\\b|裁判|评分", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("\\bprompt\\b|提示词|上下文工程", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("评测|评估|测评", java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile("智能体|大模型|模型测试|金标准|坏例|幻觉|鲁棒|红队|对齐", java.util.regex.Pattern.CASE_INSENSITIVE),
    };

    /** 站点噪声行——整行命中即删除（页头/页脚/功能块/推荐位） */
    private static final java.util.regex.Pattern NOISE_HARD = java.util.regex.Pattern.compile(
            "(?i)(关于我们|我要投稿|合作伙伴|加入我们|关注我们|联系我们|反馈投诉|业务合作|内容投稿|"
            + "登录\\s*/\\s*注册|写点什么|创作场景|记录自己日常工作|发表对生活和职场|发表随笔|从\\s*0\\s*到\\s*1\\s*详细|"
            + "个人博客、公众号|搬到这里|本文字数|阅读完需|回到顶部|站点地图|扫描二维码|扫码|票务经理|"
            + "促进软件开发及相关领域|近期会议|本次大会还策划了|日程已|大会全日程|"
            + "相关推荐|相关文章|推荐阅读|延伸阅读|更多阅读|猜你喜欢|你可能还喜欢|"
            + "版权|备案号|京ICP|京公网安备|ICP\\s*备|\\bcopyright\\b|分享到|一键分享|关注公众号|订阅我们)");

    /** 页脚起始触发词：命中后丢弃该行及之后所有行（防误删，只认“强页脚标志”） */
    private static final java.util.regex.Pattern FOOTER_TRIGGER = java.util.regex.Pattern.compile(
            "(?i)(关于我们|我要投稿|促进软件开发及相关领域|近期会议|版权所有|备案号|京ICP|京公网安备|copyright)");

    /** 短行导航/操作噪声（仅当整行极短时命中，避免误删正文短句） */
    private static final java.util.regex.Pattern NOISE_SOFT_EXACT = java.util.regex.Pattern.compile(
            "^(首页|更多|登录|注册|会议|课程|下载|快讯|活动|专题|关于|加入|发现|直播|热门|推荐|收藏|点赞|评论|举报|投稿|"
            + "后端|架构|大数据|云计算|大前端|芯片|出海|软件工程|管理|文化|视频|报告|资讯|动态|AI|大模型|人工智能|"
            + "企业动态|行业深度)$");

    /** 导航词提取：整行剥掉这些词后若剩余不足 40%，视为导航/页眉块删除 */
    private static final java.util.regex.Pattern NAV_TOKEN_STRIP = java.util.regex.Pattern.compile(
            "(?i)(首页|更多|登录|注册|会议|课程|下载|快讯|活动|专题|关于|加入|发现|直播|热门|推荐|收藏|点赞|评论|投稿|"
            + "后端|架构|大数据|云计算|大前端|芯片|算力|出海|软件工程|管理|文化|视频|报告|资讯|动态|人工智能|大模型|"
            + "企业动态|行业深度|AI|hot|new|HarmonyOS|Snowflake)");

    // ==================== jsoup DOM 级正文提取 ====================

    /** 页面噪声容器（DOM 层直接剔除，避免混入导航/评论/推荐位） */
    private static final String NOISE_SELECTOR =
            "script,style,noscript,iframe,svg,nav,header,footer,aside,form,button,"
            + ".ad,.ads,.advert,.advertisement,.banner,.sidebar,.comment,.comments,#comment,#comments,"
            + ".share,.recommend,.related,.footer,.copyright,.toolbar,.search-box,.breadcrumb,"
            + ".pagination,.back-to-top,.qrcode,.cdc-header,.cdc-footer,.mod-header,.mod-footer,"
            + ".mod-side,.mod-article-source,.mod-article-source__detail,.mod-article-tools,"
            + ".mod-comments,.related-article,.hot-article,.cdc-crumb,.mod-crumb,.cdc-sticky-header,"
            + "[aria-hidden=true]";

    /** 候选正文容器（按优先级命中，要求文本量 ≥ 500 才认） */
    private static final String[] CONTENT_SELECTORS = {
            "article", "main", "[role=main]", ".mod-article-content", ".cdc-article-editor__container",
            ".article-content", ".articleContent", ".rich_media_content", "#js_content",
            ".markdown-body", ".post-content", ".entry-content", ".article",
            ".content", "#content", "section"
    };

    /** 需要独立成行的块级标签 */
    private static final String BLOCK_TAGS = ",p,h1,h2,h3,h4,h5,h6,li,tr,pre,blockquote,dd,dt,";

    /** 纯文本 > 9999 字后截断 */
    private static final int MAX_TEXT_LEN = 10000;

    /**
     * 清理抓取到的正文，去掉站点导航/页脚/推荐位等噪声。
     * 展示给前端前也会调用一次，保证历史数据即时显示干净。
     */
    public String cleanArticleContent(String content) {
        return cleanNoise(content, null);
    }

    /**
     * 逐行过滤噪声：
     * 1. 遇到强页脚标志后整段截断
     * 2. 整行命中站点功能块关键词的行删除
     * 3. 长度 ≤ 24 且为导航类短词的行删除
     */
    private String cleanNoise(String text, String title) {
        if (StrUtil.isBlank(text)) {
            return text;
        }
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder(text.length());
        boolean dropping = false;
        for (String line : lines) {
            String t = line.trim();
            if (dropping) {
                continue;
            }
            if (StrUtil.isBlank(t)) {
                sb.append(line).append('\n');
                continue;
            }
            if (FOOTER_TRIGGER.matcher(t).find()) {
                dropping = true;
                continue;
            }
            if (title != null && t.equalsIgnoreCase(title.trim())) {
                continue;
            }
            if (NOISE_HARD.matcher(t).find()) {
                continue;
            }
            if (t.length() <= 24 && NOISE_SOFT_EXACT.matcher(t).find()) {
                continue;
            }
            // 剥掉导航词后剩余不足原行 40% → 站点导航/页眉块
            if (t.length() <= 300) {
                String stripped = NAV_TOKEN_STRIP.matcher(t).replaceAll(" ")
                        .replaceAll("[\\s·—–、。，；：｜/\\-]+", "");
                if (stripped.length() * 10L < t.length() * 4L) {
                    continue;
                }
            }
            sb.append(line).append('\n');
        }
        String result = sb.toString().replaceAll("\\n{3,}", "\n\n").trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * 判断文章是否与 AI / Agent / 评测相关
     */
    private boolean isRelevant(String title, String content) {
        String haystack = (StrUtil.nullToEmpty(title) + " " + StrUtil.nullToEmpty(content)).toLowerCase();
        if (haystack.length() > 5000) {
            haystack = haystack.substring(0, 5000);
        }
        for (java.util.regex.Pattern p : RELEVANT_PATTERNS) {
            if (p.matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 RSS feed 拉取文章并入库，返回新增条数（同步执行，供手动/定时任务调用）
     */
    public int fetchAndStore(String feedUrl, String sourceName) {
        int count = 0;
        try {
            Request request = new Request.Builder()
                    .url(feedUrl)
                    .header("User-Agent", "Evalia-AI/1.0 (RSS Reader)")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("RSS 拉取失败: {} status={}", feedUrl, response.code());
                    return 0;
                }
                String xml = response.body().string();
                count = parseAndStore(xml, sourceName);
                log.info("RSS 拉取完成: {} 新增 {} 条", feedUrl, count);
            }
        } catch (Exception e) {
            log.error("RSS 拉取异常: {}", feedUrl, e);
        }
        return count;
    }

    /**
     * 解析 RSS/Atom XML 并存储新文章
     */
    private int parseAndStore(String xml, String sourceName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        String rootTag = doc.getDocumentElement().getTagName();
        int count = 0;

        if ("rss".equals(rootTag)) {
            // RSS 2.0 格式
            NodeList channelList = doc.getElementsByTagName("channel");
            if (channelList.getLength() > 0) {
                Element channel = (Element) channelList.item(0);
                if (StrUtil.isBlank(sourceName)) {
                    NodeList titleNodes = channel.getElementsByTagName("title");
                    if (titleNodes.getLength() > 0) {
                        sourceName = titleNodes.item(0).getTextContent().trim();
                    }
                }
                NodeList items = channel.getElementsByTagName("item");
                for (int i = 0; i < items.getLength(); i++) {
                    Element item = (Element) items.item(i);
                    if (storeRssItem(item, sourceName)) {
                        count++;
                    }
                }
            }
        } else if ("feed".equals(rootTag)) {
            // Atom 格式
            if (StrUtil.isBlank(sourceName)) {
                NodeList titleNodes = doc.getElementsByTagName("title");
                if (titleNodes.getLength() > 0) {
                    sourceName = titleNodes.item(0).getTextContent().trim();
                }
            }
            NodeList entries = doc.getElementsByTagName("entry");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                if (storeAtomEntry(entry, sourceName)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean storeRssItem(Element item, String sourceName) {
        String title = getTagText(item, "title");
        String link = getTagText(item, "link");
        String description = getTagText(item, "description");
        // 部分源（如美团技术团队）把正文放在 <content:encoded> 而非 description
        String encoded = getTagText(item, "content:encoded");
        String pubDate = getTagText(item, "pubDate");
        String author = getTagText(item, "author");
        if (StrUtil.isBlank(author)) {
            author = getTagText(item, "dc:creator");
        }

        if (StrUtil.isBlank(title) || StrUtil.isBlank(link)) {
            return false;
        }

        // 去重
        LambdaQueryWrapper<EvalArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EvalArticle::getSourceUrl, link);
        if (articleMapper.selectCount(wrapper) > 0) {
            return false;
        }

        EvalArticle article = new EvalArticle();
        article.setTitle(title.trim());
        article.setSourceName(sourceName);
        article.setSourceUrl(link.trim());
        article.setAuthor(StrUtil.isNotBlank(author) ? author.trim() : null);
        article.setContent(StrUtil.isNotBlank(encoded) ? htmlToText(encoded) : (StrUtil.isNotBlank(description) ? htmlToText(description) : null));
        article.setPublishedAt(parseDateTime(pubDate));
        article.setStatus(1);

        // RSS 无正文时，抓取原文页面提取文字（保证点击可读）
        ensureContent(article);

        // 只收录 AI / Agent / 评测相关内容
        if (!isRelevant(article.getTitle(), article.getContent())) {
            log.info("RSS 条目与评测知识无关，跳过: {}", article.getTitle());
            return false;
        }

        articleMapper.insert(article);
        return true;
    }

    private boolean storeAtomEntry(Element entry, String sourceName) {
        String title = getTagText(entry, "title");
        String link = "";
        NodeList linkNodes = entry.getElementsByTagName("link");
        for (int i = 0; i < linkNodes.getLength(); i++) {
            Element linkEl = (Element) linkNodes.item(i);
            String rel = linkEl.getAttribute("rel");
            if ("alternate".equals(rel) || StrUtil.isBlank(rel)) {
                link = linkEl.getAttribute("href");
                break;
            }
        }
        if (StrUtil.isBlank(link) && linkNodes.getLength() > 0) {
            link = ((Element) linkNodes.item(0)).getAttribute("href");
        }
        String summary = getTagText(entry, "summary");
        String content = getTagText(entry, "content");
        String published = getTagText(entry, "published");
        if (StrUtil.isBlank(published)) {
            published = getTagText(entry, "updated");
        }
        String author = "";
        NodeList authorNodes = entry.getElementsByTagName("author");
        if (authorNodes.getLength() > 0) {
            Element authorEl = (Element) authorNodes.item(0);
            author = getTagText(authorEl, "name");
        }

        if (StrUtil.isBlank(title) || StrUtil.isBlank(link)) {
            return false;
        }

        // 去重
        LambdaQueryWrapper<EvalArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EvalArticle::getSourceUrl, link);
        if (articleMapper.selectCount(wrapper) > 0) {
            return false;
        }

        EvalArticle article = new EvalArticle();
        article.setTitle(title.trim());
        article.setSourceName(sourceName);
        article.setSourceUrl(link.trim());
        article.setAuthor(StrUtil.isNotBlank(author) ? author.trim() : null);
        article.setContent(StrUtil.isNotBlank(content) ? htmlToText(content) : (StrUtil.isNotBlank(summary) ? htmlToText(summary) : null));
        article.setPublishedAt(parseDateTime(published));
        article.setStatus(1);

        // RSS 无正文时，抓取原文页面提取文字（保证点击可读）
        ensureContent(article);

        // 只收录 AI / Agent / 评测相关内容
        if (!isRelevant(article.getTitle(), article.getContent())) {
            log.info("RSS 条目与评测知识无关，跳过: {}", article.getTitle());
            return false;
        }

        articleMapper.insert(article);
        return true;
    }

    /** 单篇文章导入（大厂团队无 RSS/订阅源时的替代方案）：
     * 抓取指定 URL 页面，提取标题与正文，AI/Agent/评测相关则入库
     * @return 导入成功返回 true；不相关或失败返回 false
     */
    public boolean importArticle(String url, String sourceName) {
        if (StrUtil.isBlank(url)) {
            throw new BusinessException("URL 不能为空");
        }
        try {
            // 去重
            LambdaQueryWrapper<EvalArticle> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(EvalArticle::getSourceUrl, url.trim());
            if (articleMapper.selectCount(wrapper) > 0) {
                log.info("文章已存在，跳过导入: {}", url);
                return false;
            }

            String html = fetchRawHtml(url);
            if (StrUtil.isBlank(html)) {
                log.warn("页面抓取为空: {}", url);
                return false;
            }
            // 提取标题（<title> 或 og:title）
            String title = extractTitle(html);
            if (StrUtil.isBlank(title)) {
                log.warn("无法提取标题: {}", url);
                return false;
            }
            // 提取正文
            String content = extractText(html);
            if (StrUtil.isBlank(content)) {
                log.warn("无法提取正文: {}", url);
                return false;
            }
            // 相关性过滤
            if (!isRelevant(title, content)) {
                log.info("文章与评测知识无关，跳过导入: {}", title);
                return false;
            }

            EvalArticle article = new EvalArticle();
            article.setTitle(title.trim());
            article.setSourceName(StrUtil.isNotBlank(sourceName) ? sourceName.trim() : "手工导入");
            article.setSourceUrl(url.trim());
            article.setContent(content);
            article.setPublishedAt(LocalDateTime.now());
            article.setStatus(1);
            articleMapper.insert(article);
            log.info("单篇文章导入成功: {}", title);
            return true;
        } catch (Exception e) {
            log.error("单篇文章导入异常: {}", url, e);
            return false;
        }
    }

    /**
     * 腾讯云开发者社区专栏抓取（source_type=tencent）。
     * 微信/腾讯系无公开 RSS，此接口与 RSSHub 的 /tencent/cloud/developer/column 同源 ——
     * 直接请求其公开 JSON 接口，无需自建 RSSHub。
     * 源地址格式：https://cloud.tencent.com/developer/api/home/article-list?classifyId=0
     * @return 新增文章数
     */
    public int fetchTencentColumn(EvalRssSource source) {
        int classifyId = 0;
        try {
            String url = StrUtil.nullToEmpty(source.getFeedUrl());
            int idx = url.indexOf("classifyId=");
            if (idx >= 0) {
                classifyId = Integer.parseInt(url.substring(idx + "classifyId=".length()).replaceAll("\\D.*", ""));
            }
        } catch (Exception e) {
            classifyId = 0;
        }
        int finalClassifyId = classifyId;
        try {
            JSONObject payload = new JSONObject();
            payload.set("classifyId", finalClassifyId);
            payload.set("page", 1);
            payload.set("pagesize", 20);
            payload.set("type", "");
            Request request = new Request.Builder()
                    .url("https://cloud.tencent.com/developer/api/home/article-list")
                    .header("User-Agent", BROWSER_UA)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), MediaType.parse("application/json; charset=utf-8")))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("腾讯云专栏拉取失败: {} status={}", source.getFeedUrl(), response.code());
                    return 0;
                }
                JSONObject json = JSONUtil.parseObj(response.body().string());
                JSONArray list = json.getJSONArray("list");
                if (list == null || list.isEmpty()) {
                    return 0;
                }
                int count = 0;
                for (Object o : list) {
                    JSONObject item = (JSONObject) o;
                    String title = item.getStr("title");
                    Integer articleId = item.getInt("articleId");
                    if (StrUtil.isBlank(title) || articleId == null) {
                        continue;
                    }
                    String link = "https://cloud.tencent.com/developer/article/" + articleId;

                    // 去重
                    LambdaQueryWrapper<EvalArticle> w = new LambdaQueryWrapper<>();
                    w.eq(EvalArticle::getSourceUrl, link);
                    if (articleMapper.selectCount(w) > 0) {
                        continue;
                    }

                    EvalArticle article = new EvalArticle();
                    article.setTitle(title.trim());
                    article.setSourceName(source.getSourceName());
                    article.setSourceUrl(link);
                    JSONObject authorObj = item.getJSONObject("author");
                    if (authorObj != null && StrUtil.isNotBlank(authorObj.getStr("nickname"))) {
                        article.setAuthor(authorObj.getStr("nickname"));
                    }
                    Long createTime = item.getLong("createTime", 0L);
                    if (createTime != null && createTime > 0) {
                        article.setPublishedAt(LocalDateTime.ofInstant(Instant.ofEpochSecond(createTime), ZoneId.systemDefault()));
                    }
                    article.setContent(htmlToText(item.getStr("summary")));
                    article.setStatus(1);

                    // 摘要过短时抓取全文（jsoup DOM 提取），保证详情可读
                    ensureContent(article);

                    if (!isRelevant(article.getTitle(), article.getContent())) {
                        log.info("腾讯云条目与评测知识无关，跳过: {}", article.getTitle());
                        continue;
                    }
                    articleMapper.insert(article);
                    count++;
                }
                log.info("腾讯云专栏拉取完成: classifyId={} 新增 {} 条", finalClassifyId, count);
                return count;
            }
        } catch (Exception e) {
            log.error("腾讯云专栏拉取异常: {}", source.getFeedUrl(), e);
            return 0;
        }
    }

    private String fetchRawHtml(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String html = response.body().source().readUtf8();
            if (html.length() > 5_000_000) {
                html = html.substring(0, 5_000_000);
            }
            return html;
        }
    }

    private String extractTitle(String html) {
        java.util.regex.Matcher og = java.util.regex.Pattern.compile("(?is)<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (og.find()) {
            String t = htmlDecode(og.group(1)).trim();
            if (StrUtil.isNotBlank(t)) {
                return strimTags(t);
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?is)<title[^>]*>([^<]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) {
            return htmlDecode(m.group(1)).trim();
        }
        return null;
    }

    private String extractText(String html) {
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(html);
            org.jsoup.nodes.Element root = findContentRoot(doc);
            String text = elementToText(root);
            return normalizeText(text);
        } catch (Exception e) {
            log.warn("jsoup 提取正文失败，使用正则兜底: {}", e.getMessage());
            String t = html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                    .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                    .replaceAll("(?i)</?(p|div|h[1-6]|li|br|tr|section|article)[^>]*>", "\n")
                    .replaceAll("(?s)<[^>]+>", " ");
            t = htmlDecode(t);
            t = t.replaceAll("[\\t\\r]+", "").replaceAll(" {2,}", " ").replaceAll("\\n{3,}", "\n\n").trim();
            if (t.length() > MAX_TEXT_LEN) {
                t = t.substring(0, MAX_TEXT_LEN);
            }
            return cleanNoise(t, null);
        }
    }

    /**
     * 在文档中定位正文容器：
     * 先整篇移除噪声元素，再按候选选择器优先级找一个文本量足够的容器，
     * 找不到就退回 body。
     */
    private org.jsoup.nodes.Element findContentRoot(org.jsoup.nodes.Document doc) {
        doc.select(NOISE_SELECTOR).remove();
        for (String selector : CONTENT_SELECTORS) {
            for (org.jsoup.nodes.Element candidate : doc.select(selector)) {
                if (candidate.text().length() >= 500) {
                    return candidate;
                }
            }
        }
        return doc.body();
    }

    /**
     * 把元素树转成带段落换行的纯文本：
     * 块级标签（p/h/li/pre 等）整块成行，br 视作换行，其余按叶子节点逐段输出。
     */
    private String elementToText(org.jsoup.nodes.Element root) {
        StringBuilder sb = new StringBuilder(root.text().length() + 64);
        walkText(root, sb);
        return sb.toString();
    }

    private void walkText(org.jsoup.nodes.Element el, StringBuilder sb) {
        String tag = el.tagName();
        if ("br".equals(tag)) {
            sb.append('\n');
            return;
        }
        // 块级标签：整块文本 + 换行，不再递归（避免重复拼接）
        if (BLOCK_TAGS.contains("," + tag + ",")) {
            String t = el.text().trim();
            if (!t.isEmpty()) {
                sb.append(t).append('\n');
            }
            return;
        }
        // 元素自身直接文本（如 <div>文字 <a>链接</a></div> 的“文字”）
        String own = el.ownText();
        if (!own.trim().isEmpty()) {
            sb.append(own.trim()).append('\n');
        }
        for (org.jsoup.nodes.Element child : el.children()) {
            walkText(child, sb);
        }
    }

    /** 规整空白 + 截断 + 站点噪声清理 */
    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        String t = text.replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (t.length() > MAX_TEXT_LEN) {
            t = t.substring(0, MAX_TEXT_LEN) + "…";
        }
        return cleanNoise(t, null);
    }

    /**
     * HTML/纯文本 → 纯文本。
     * 若内容不含标签则视为纯文本原样保留（保留段落换行，避免被 HTML 解析折叠空白）；
     * 含标签则走 jsoup DOM 提取。
     */
    private String htmlToText(String htmlOrText) {
        if (StrUtil.isBlank(htmlOrText)) {
            return null;
        }
        String s = htmlOrText.trim();
        boolean looksLikeHtml = s.matches("(?is).*<[a-z]/?[^>]*>.*");
        if (!looksLikeHtml) {
            String t = s.replaceAll("\\r\\n?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
            return t.length() > MAX_TEXT_LEN ? t.substring(0, MAX_TEXT_LEN) + "…" : t;
        }
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(s);
            doc.select(NOISE_SELECTOR).remove();
            String t = normalizeText(elementToText(doc.body()));
            return t != null && !t.isEmpty() ? t : null;
        } catch (Exception e) {
            log.warn("HTML 转文本失败，退回原文: {}", e.getMessage());
            return s.length() > MAX_TEXT_LEN ? s.substring(0, MAX_TEXT_LEN) : s;
        }
    }

    private String htmlDecode(String s) {
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "'").replace("&ldquo;", "\"").replace("&rdquo;", "\"");
    }

    private String strimTags(String s) {
        return s.replaceAll("(?s)<[^>]+>", " ").trim();
    }

    /**
     * 若文章无正文或正文过短，抓取原文页面提取文本
     */
    private void ensureContent(EvalArticle article) {
        if (article.getContent() != null && article.getContent().trim().length() > 200) {
            return;
        }
        try {
            String fullText = fetchFullText(article.getSourceUrl());
            if (StrUtil.isNotBlank(fullText)) {
                article.setContent(fullText);
            }
        } catch (Exception e) {
            log.warn("抓取全文失败: {}", article.getSourceUrl(), e);
        }
    }

    /**
     * 抓取文章页面并提取纯文本（jsoup DOM 提取正文，剔除导航/页脚等噪声）
     */
    private String fetchFullText(String url) throws Exception {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String html = response.body().source().readUtf8();
            if (html.length() > 5_000_000) {
                html = html.substring(0, 5_000_000);
            }
            return extractText(html);
        }
    }

    /**
     * 为文章生成 AI 摘要
     */
    public String summarizeArticle(Long articleId, Long modelConfigId) {
        EvalArticle article = articleMapper.selectById(articleId);
        if (article == null) {
            throw new BusinessException("文章不存在");
        }
        if (StrUtil.isBlank(article.getContent())) {
            throw new BusinessException("文章正文为空，无法生成摘要");
        }

        // 选择模型
        EvalModelConfig modelConfig = null;
        if (modelConfigId != null) {
            modelConfig = modelConfigMapper.selectById(modelConfigId);
        }
        if (modelConfig == null) {
            // 取第一个可用模型
            LambdaQueryWrapper<EvalModelConfig> w = new LambdaQueryWrapper<>();
            w.last("LIMIT 1");
            modelConfig = modelConfigMapper.selectOne(w);
        }
        if (modelConfig == null) {
            throw new BusinessException("没有可用的模型，请先配置模型");
        }

        // 取正文前 3000 字（避免超长文本）
        String text = article.getContent();
        if (text.length() > 3000) {
            text = text.substring(0, 3000) + "...";
        }

        String prompt = "请对以下评测相关技术文章进行精炼总结，要求：\n" +
                "1. 用 3-5 个要点概括核心内容\n" +
                "2. 每个要点 1-2 句话\n" +
                "3. 突出与大模型评测/Agent评测相关的关键方法、工具、结论\n" +
                "4. 输出纯文本，不要用 Markdown 格式\n\n" +
                "文章标题：" + article.getTitle() + "\n" +
                "来源：" + (StrUtil.isNotBlank(article.getSourceName()) ? article.getSourceName() : "未知") + "\n\n" +
                "文章正文：\n" + text;

        String systemPrompt = "你是一位专业的技术文章分析师，擅长从大模型评测和Agent评测相关文章中提炼核心观点。";

        String summary = llmApiClient.chat(modelConfig, systemPrompt, prompt);

        // 更新文章摘要
        article.setSummary(summary);
        articleMapper.updateById(article);

        return summary;
    }

    private String getTagText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return null;
    }

    private LocalDateTime parseDateTime(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        try {
            java.text.SimpleDateFormat rfc822 = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US);
            Date date = rfc822.parse(dateStr.trim());
            return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr.trim(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e2) {
                try {
                    java.text.SimpleDateFormat simple = new java.text.SimpleDateFormat("yyyy-MM-dd");
                    Date date = simple.parse(dateStr.trim().substring(0, 10));
                    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                } catch (Exception e3) {
                    log.warn("无法解析日期: {}", dateStr);
                    return null;
                }
            }
        }
    }
}
