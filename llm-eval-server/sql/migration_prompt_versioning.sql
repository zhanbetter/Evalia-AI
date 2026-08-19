-- ============================================================
-- Migration: 评估器版本化 + 输出格式规范(strict_output) 支持
-- 1) eval_prompt 增加 version 列
-- 2) 新建 eval_prompt_version 快照表
-- 3) eval_task_prompt 增加 Prompt 快照列（任务运行时使用冻结版本，不受后续编辑影响）
-- ============================================================

-- 1. eval_prompt 版本号（默认 1，更新时自动 +1）
ALTER TABLE eval_prompt
  ADD COLUMN version INT DEFAULT 1 COMMENT '版本号（更新时自动+1）' AFTER id;

-- 2. 评估器版本快照表
CREATE TABLE IF NOT EXISTS eval_prompt_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_id BIGINT NOT NULL COMMENT '关联评估器ID',
    version INT NOT NULL COMMENT '版本号',
    name VARCHAR(128) NOT NULL COMMENT 'Prompt名称',
    description VARCHAR(512) DEFAULT '' COMMENT '描述',
    prompt_template TEXT NOT NULL COMMENT '评测Prompt模板',
    dimensions_config TEXT NULL COMMENT '结构化维度配置JSON（含 strict_output）',
    evaluation_mode VARCHAR(16) DEFAULT 'quality' COMMENT 'quality/reference',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_prompt_id (prompt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估器版本快照';

-- 3. eval_task_prompt 冻结快照列（任务创建时写入，JudgeWorker优先读快照）
ALTER TABLE eval_task_prompt
  ADD COLUMN prompt_version INT DEFAULT NULL COMMENT '创建任务时评估器版本快照',
  ADD COLUMN prompt_name VARCHAR(128) DEFAULT '' COMMENT '评估器名称快照',
  ADD COLUMN prompt_template TEXT NULL COMMENT '评估Prompt模板快照',
  ADD COLUMN dimensions_config TEXT NULL COMMENT '维度配置JSON快照';

-- 回填：为已存在的评估器初始化 version、为已存在任务回填当前快照
UPDATE eval_prompt SET version = 1 WHERE version IS NULL OR version < 1;
UPDATE eval_task_prompt tp
    JOIN eval_prompt p ON p.id = tp.prompt_id
SET tp.prompt_version = IFNULL(p.version, 1),
    tp.prompt_name   = IFNULL(p.name, ''),
    tp.prompt_template = IFNULL(p.prompt_template, ''),
    tp.dimensions_config = IFNULL(p.dimensions_config, '')
WHERE tp.prompt_template IS NULL OR tp.prompt_template = '';