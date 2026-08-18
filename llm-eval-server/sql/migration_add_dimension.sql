-- ============================================================
-- 单维度判定模式 数据库迁移脚本
-- 在 eval_judge_result 表新增 dimension 字段
-- 用途：评估器改为"每个维度单独调用裁判模型"后，
--       每个 (case × 维度) 存一条记录，dimension 字段标记是哪个维度；
--       dimension IS NULL 的记录表示整体判定结果
-- ============================================================

ALTER TABLE eval_judge_result
    ADD COLUMN dimension VARCHAR(128) DEFAULT NULL COMMENT '评测维度(NULL=整体判定记录，非NULL=单维度判定记录)' AFTER prompt_id;

-- 补充索引，加速按维度查询
ALTER TABLE eval_judge_result
    ADD INDEX idx_dimension (dimension);
