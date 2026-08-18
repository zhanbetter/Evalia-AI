-- ============================================================
-- 数据集增加"是否含模型结果"字段
-- 用途：明确回答来源
--   has_model_response = 1 → 数据集已存被测模型的回答，评测时直接读取
--   has_model_response = 0 → 数据集只有问题，评测时调用被测模型 API 生成
--
-- 与 has_reference（含参考答案）区分：
--   has_reference        = 数据集有无标准答案（决定评估模式）
--   has_model_response   = 数据集有无模型回答（决定回答来源）
-- ============================================================

ALTER TABLE eval_dataset
    ADD COLUMN has_model_response TINYINT DEFAULT 0 COMMENT '是否含模型结果: 1-有(评测直接读), 0-无(评测时调API生成)' AFTER has_reference;
