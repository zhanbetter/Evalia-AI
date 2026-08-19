-- ============================================================
-- 评测知识 · 预置 RSS 订阅源（可选执行，已在 migration 建表的场景）
-- 全为实测可用的 AI/Agent/评测内容源（美团/Tencent 于 2026-08 验证）
-- ============================================================
SET NAMES utf8mb4;

-- 需要 v2 迁移先加 source_type 列；若表是 v1 时代建的，请先执行 migration_rss_source_v2.sql
INSERT IGNORE INTO eval_rss_source (source_name, feed_url, source_type, description, status) VALUES
('InfoQ 中文', 'https://www.infoq.cn/feed', 'rss', '软件开发与 AI/Agent 实践资讯，RSS 2.0', 1),
('阮一峰 · 科技爱好者周刊', 'https://www.ruanyifeng.com/blog/atom.xml', 'rss', '每周科技 + AI 大摘要，Atom 格式，含正文', 1),
('Hugging Face Blog', 'https://huggingface.co/blog/feed.xml', 'rss', '开源模型与 AI 研究博客（RSS 无正文，走全文兜底抓取）', 1),
('美团技术团队', 'https://tech.meituan.com/feed/', 'rss', '美团官方 RSS feed，含全文(content:encoded)，AI/Agent 与后端内容居多', 1),
('腾讯云开发者社区 · 全部专栏', 'https://cloud.tencent.com/developer/api/home/article-list?classifyId=0', 'tencent', '腾讯云开发者社区专栏文章（JSON 接口，classifyId=0 表示全部）', 1);