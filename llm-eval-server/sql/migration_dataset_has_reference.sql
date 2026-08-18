-- ============================================================
-- 数据集增加"是否含参考答案"模式标记
-- 用途：区分评测路径
--   has_reference = 1 → 有标准答案，AI 对照答案判对错，走"人机对比"
--   has_reference = 0 → 无标准答案（自由判断），AI 按标准判好坏，走"人工标注"
-- ============================================================

ALTER TABLE eval_dataset
    ADD COLUMN has_reference TINYINT DEFAULT 1 COMMENT '是否含参考答案: 1-有(对照评测), 0-无(自由判断)' AFTER column_mapping;
