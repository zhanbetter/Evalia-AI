-- ============================================================
-- eval_rss_source 升级 v2：支持专题源类型（腾讯云 JSON 接口）
-- 适用于已执行过 migration_rss_source.sql 的已有环境
-- 新克隆环境无需执行（init.sql 已含 source_type 列）
-- 注意：只能执行一次；重复执行会报“列已存在”或 INSERT 幂等（INSERT IGNORE）
-- ============================================================
SET NAMES utf8mb4;

-- 1. 加列：source_type rss-标准feed / tencent-腾讯云JSON接口
ALTER TABLE eval_rss_source ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'rss' COMMENT '源类型：rss-标准feed，tencent-腾讯云JSON接口' AFTER feed_url;

-- 2. 预置美团官方 feed + 腾讯云开发者社区专栏
--    （美团 feed 已实测 HTTP 200 且含全文 content:encoded；腾讯走其公开 JSON 接口，与 RSSHub 同源）
INSERT IGNORE INTO eval_rss_source (source_name, feed_url, source_type, description, status) VALUES
('美团技术团队', 'https://tech.meituan.com/feed/', 'rss', '美团官方 RSS feed，含全文(content:encoded)，AI/Agent 与后端内容居多', 1),
('腾讯云开发者社区 · 全部专栏', 'https://cloud.tencent.com/developer/api/home/article-list?classifyId=0', 'tencent', '腾讯云开发者社区专栏文章（JSON 接口，classifyId=0 表示全部）', 1);