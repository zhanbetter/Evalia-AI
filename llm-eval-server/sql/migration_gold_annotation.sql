-- ============================================================
-- migration_gold_annotation.sql
-- 数据集条目级金标准标注台（脱离任务/被测模型的独立标注）
--
-- 与 eval_gold_label 的区别：
--   eval_gold_label      按(model_config_id, dataset_item_id)挂「该被测模型输出在此样本上的裁决结论」
--   eval_gold_annotation 按(dataset_item_id, annotator)存「多位标注者对该条目的独立判定」，
--                        用于标注覆盖率/一致率/Fleiss Kappa 等一致性统计，多数表决出条目级金标准
--
-- 幂等：CREATE TABLE IF NOT EXISTS，可重复执行
-- ============================================================
CREATE TABLE IF NOT EXISTS eval_gold_annotation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    annotator VARCHAR(64) NOT NULL COMMENT '标注者',
    role VARCHAR(32) DEFAULT 'ANNOTATOR' COMMENT '标注角色: ANNOTATOR-标注员 EXPERT-专家 REVIEWER-复核',
    is_badcase TINYINT NOT NULL COMMENT '标注结论: 1-badcase 0-goodcase',
    comment VARCHAR(500) COMMENT '标注备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_goldanno_item (dataset_item_id, annotator),
    KEY idx_goldanno_item (dataset_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='金标准标注（条目级多标注者投片，脱离任务/模型的独立标注台）';