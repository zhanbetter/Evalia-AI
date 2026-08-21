-- ============================================================
-- migration_user_auth.sql  平台用户登录/注册鉴权（幂等）
--
-- 适用：已有环境追平登录功能。全新部署用 init.sql（已并入本表）。
-- 执行方式（在项目根目录运行；PowerShell 的 < 是保留字符，不能用引号外的 < 重定向）：
--   mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval -e "source llm-eval-server/sql/migration_user_auth.sql"
--   cmd /c "mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval < llm-eval-server\sql\migration_user_auth.sql"
-- ============================================================
SET NAMES utf8mb4;

-- 平台用户：登录/注册/鉴权。首个注册用户由代码自动赋予 ADMIN 角色。
CREATE TABLE IF NOT EXISTS eval_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL COMMENT '登录名（唯一）',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希（hutool BCrypt，$2a$ 前缀）',
    nickname VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    role VARCHAR(20) DEFAULT 'USER' COMMENT '角色: ADMIN-管理员 USER-普通用户',
    status TINYINT DEFAULT 1 COMMENT '状态: 1-启用 0-禁用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户（登录/注册/鉴权）';

SELECT 'migration_user_auth 执行完毕：eval_user 表已就绪。' AS result;