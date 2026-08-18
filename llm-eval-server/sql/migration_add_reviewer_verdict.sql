-- ============================================================
-- 多角色校验体系 数据库迁移脚本
-- 支持：人机校验（AI vs 人工）+ 人人校验（多个人互相独立判定）
--
-- 核心表 eval_reviewer_verdict：
--   每个校验人对每个样本提交一条独立判定
--   唯一键 (task + model + prompt + item + reviewer) 保证同一人对同一样本只能有一条
--   （再次提交 = 修改自己的判定）
--
-- 一致率计算：
--   人机一致率 = 所有"人工判定 == AI判定"的数量 / 人工判定总数
--   人人一致率 = 被≥2人校验的样本中，"所有校验人判定一致"的样本数 / 多校验人样本数
-- ============================================================

CREATE TABLE IF NOT EXISTS eval_reviewer_verdict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评测任务ID',
    model_config_id BIGINT NOT NULL COMMENT '被测模型ID',
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt ID',
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '校验人',
    is_badcase_human TINYINT DEFAULT NULL COMMENT '该校验人的判定: 1-badcase 0-非badcase',
    comment VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_review (task_id, model_config_id, prompt_id, dataset_item_id, reviewer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多角色人工校验判定';
