-- ============================================================
-- eval_article 评测知识文章表（增量迁移）
-- 已有环境执行此脚本，新克隆用 init.sql 即可
-- ============================================================
-- 必须：连接字符集 utf8mb4，否则中文注释会乱码
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS eval_article (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL COMMENT '文章标题',
    source_name VARCHAR(200) COMMENT '来源团队/博客名',
    source_url VARCHAR(1000) NOT NULL COMMENT '原文链接（去重用）',
    author VARCHAR(200) COMMENT '作者',
    tags VARCHAR(500) COMMENT '标签，逗号分隔',
    summary TEXT COMMENT 'AI 生成摘要',
    content LONGTEXT COMMENT '文章正文（HTML 或纯文本）',
    published_at DATETIME COMMENT '原文发布时间',
    fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    status TINYINT DEFAULT 1 COMMENT '状态 1-正常 0-隐藏',
    UNIQUE KEY uk_url (source_url(255)),
    INDEX idx_published (published_at DESC),
    INDEX idx_source (source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测知识文章';
