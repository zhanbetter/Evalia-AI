-- ============================================================
-- eval_rss_source RSS 订阅源表（增量迁移）
-- 已有环境执行此脚本，新克隆用 init.sql 即可
-- ============================================================
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS eval_rss_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(200) NOT NULL COMMENT '来源名称',
    feed_url VARCHAR(1000) NOT NULL COMMENT 'RSS/Atom 地址（tencent 类型时为腾讯云 JSON 接口地址）',
    source_type VARCHAR(20) NOT NULL DEFAULT 'rss' COMMENT '源类型：rss-标准feed，tencent-腾讯云JSON接口',
    description VARCHAR(500) COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '1-启用 0-停用',
    last_fetched_at DATETIME COMMENT '上次拉取时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_feed_url (feed_url(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RSS 订阅源';