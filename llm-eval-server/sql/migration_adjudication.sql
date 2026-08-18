-- ============================================================
-- 人工校验双链路重构 数据库迁移脚本
--
-- 链路一：人工标注（普通评委 + 专家裁决 → 产金标准）
--   - eval_reviewer_verdict 加 role 字段：normal=普通评委，expert=专家
--   - 新增 eval_gold_label 表：专家裁决后的最终结论（金标准）
--
-- 链路二：人机对比（AI vs 金标准 → 算不一致率）
--   - 复用 eval_gold_label 作为真值，和 eval_judge_result 的 AI 判定对比
-- ============================================================

-- 1. reviewer_verdict 加角色字段
ALTER TABLE eval_reviewer_verdict
    ADD COLUMN role VARCHAR(16) DEFAULT 'normal' COMMENT '校验人角色: normal-普通评委, expert-专家' AFTER reviewer;

-- 2. 金标准表（专家裁决后的最终结论）
CREATE TABLE IF NOT EXISTS eval_gold_label (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评测任务ID',
    model_config_id BIGINT NOT NULL COMMENT '被测模型ID',
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt ID',
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    is_badcase TINYINT NOT NULL COMMENT '金标准判定: 1-badcase 0-非badcase',
    has_disagreement TINYINT DEFAULT 0 COMMENT '评委是否有分歧: 1-有 0-无(一致)',
    adjudicator VARCHAR(64) COMMENT '裁决专家',
    adjudicate_comment VARCHAR(500) COMMENT '裁决备注',
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING-待裁决(有分歧), CONFIRMED-已确认',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gold (task_id, model_config_id, prompt_id, dataset_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='金标准（专家裁决后的最终结论）';
