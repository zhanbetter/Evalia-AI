-- ============================================================
-- migration_async_job.sql  异步预分析任务表
--
-- 用途：AI识别成规则 / 润色多维度 / 数据重复检测 三个耗时操作
--       从同步HTTP改为异步任务模式（提交→后台线程池执行→前端轮询）。
-- 幂等：可重复执行。
-- ============================================================
SET NAMES utf8mb4;
SET @db = DATABASE();

CREATE TABLE IF NOT EXISTS eval_async_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type VARCHAR(30) NOT NULL COMMENT '任务类型: PARSE_DIMENSIONS/POLISH/DETECT_DUPLICATES',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED',
    progress INT DEFAULT 0 COMMENT '进度 0-100',
    progress_text VARCHAR(255) DEFAULT '' COMMENT '进度描述（如 正在润色维度 2/5）',
    payload TEXT COMMENT '请求参数JSON，如 {"modelId":1,"text":"..."/"dimensionsConfig":"..."/"datasetId":5}',
    result_data LONGTEXT NULL COMMENT '结果JSON',
    error_message TEXT NULL COMMENT '失败信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    INDEX idx_type_status (job_type, status),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步预分析任务（AI识别/润色/重复检测）';

SELECT 'migration_async_job 执行完毕：eval_async_job 表已就绪。' AS result;