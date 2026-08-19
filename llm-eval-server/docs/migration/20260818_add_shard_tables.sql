-- =============================================
-- P0: 分片调度 + 状态机 + 异常隔离
-- 日期: 2026-08-18
-- =============================================

-- 1. eval_task 新增分片总数字段
ALTER TABLE eval_task ADD COLUMN shard_count INT DEFAULT 0 COMMENT '分片总数（大规模评测拆分后写入，小任务为0）';

-- 2. 评测任务分片表
CREATE TABLE IF NOT EXISTS eval_task_shard (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id       BIGINT        NOT NULL COMMENT '任务ID',
    shard_index   INT           NOT NULL COMMENT '分片序号（从0开始）',
    shard_size    INT           NOT NULL DEFAULT 0 COMMENT '分片大小（case数）',
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '分片状态: PENDING/RUNNING/COMPLETED/FAILED',
    completed_count INT         DEFAULT 0 COMMENT '已完成case数（含成功+失败）',
    failed_count  INT           DEFAULT 0 COMMENT '失败case数',
    retry_count   INT           DEFAULT 0 COMMENT '重试次数',
    shard_data    TEXT          COMMENT '分片数据JSON（序列化的CaseItem列表）',
    error_message TEXT          COMMENT '失败原因',
    created_at    DATETIME      DEFAULT CURRENT_TIMESTAMP,
    finished_at   DATETIME      NULL,
    UNIQUE KEY uk_task_shard (task_id, shard_index),
    KEY idx_task_status (task_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测任务分片';

-- 3. Kafka topic 配置（需在 Kafka 中创建）
-- bin/kafka-topics.sh --create --topic eval-shard-execute --partitions 6 --replication-factor 1
