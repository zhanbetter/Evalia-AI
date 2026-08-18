-- ============================================================
-- 人工校验功能 数据库迁移脚本
-- 用途：校准 AI 裁判。人工抽查样本并标记 badcase/goodcase，
--       计算 AI 与人工的一致率，让 badcase 率具有可信度。
-- ============================================================

CREATE TABLE IF NOT EXISTS eval_human_review (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评测任务ID',
    model_config_id BIGINT NOT NULL COMMENT '被测模型ID',
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt ID',
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    is_badcase_ai TINYINT DEFAULT NULL COMMENT 'AI判定: 1-badcase 0-非badcase',
    is_badcase_human TINYINT DEFAULT NULL COMMENT '人工判定: 1-badcase 0-非badcase',
    agree TINYINT DEFAULT NULL COMMENT '人工与AI是否一致: 1-一致 0-不一致',
    comment VARCHAR(500) DEFAULT NULL COMMENT '人工备注',
    reviewer VARCHAR(64) DEFAULT NULL COMMENT '校验人',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_model_prompt_item (task_id, model_config_id, prompt_id, dataset_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人工校验记录';
