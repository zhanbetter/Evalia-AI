-- ============================================================
-- migration_user_ownership.sql  数据归属（created_by）+ 删除保护（幂等）
--
-- 给四张核心用户数据表加 created_by（创建者 ID），配合后端删除保护：
--   eval_dataset / eval_model_config / eval_prompt / eval_task
-- 语义：created_by NULL = 历史无归属数据，仅管理员可删；有归属 = 创建者或管理员可删。
--
-- 适用：已有环境追平归属字段。全新部署用 init.sql（四表已含 created_by 列）。
-- 执行方式（在项目根目录运行；PowerShell 的 < 是保留字符，不能用引号外的 < 重定向）：
--   mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval -e "source llm-eval-server/sql/migration_user_ownership.sql"
--   cmd /c "mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval < llm-eval-server\sql\migration_user_ownership.sql"
-- ============================================================
SET NAMES utf8mb4;
SET @db = DATABASE();

-- 1) eval_dataset.created_by
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_dataset' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_dataset ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER total_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 2) eval_model_config.created_by
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_model_config' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_model_config ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER status',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3) eval_prompt.created_by
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_prompt' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_prompt ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER status',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4) eval_task.created_by
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据'' AFTER progress',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SELECT 'migration_user_ownership 执行完毕：四张核心表已具备 created_by 归属字段。' AS result;