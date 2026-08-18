-- ============================================================
-- 金标准解耦：从"评测任务级别"改为"模型+样本级别"
--
-- 原来金标准唯一键 = (task_id, model_config_id, prompt_id, dataset_item_id)
--   → 每个任务要重新裁决，无法复用
-- 现在金标准唯一键 = (model_config_id, dataset_item_id)
--   → 同一模型对同一样本的判定只裁一次，跨任务复用
--   → task_id/prompt_id 保留用于追溯，但不作为唯一键
-- ============================================================

ALTER TABLE eval_gold_label
    DROP INDEX uk_gold;

ALTER TABLE eval_gold_label
    ADD UNIQUE KEY uk_gold (model_config_id, dataset_item_id);
