-- ============================================================
-- migration_patch_all.sql  全量幂等库结构补丁（一键追平所有迁移）
--
-- 适用：已有基础表、但漏跑了迭代迁移的本地/生产库。
--       全新部署无需跑本文件，直接执行 init.sql 即可。
--
-- 本文件合并了 sql/ 目录下全部 15 批迁移，全部幂等可重复执行：
--   1) eval_judge_result.dimension + idx_dimension / idx_badcase   (add_dimension)
--   2) eval_human_review 表                                        (add_human_review)
--   3) eval_reviewer_verdict 表 + role 列                          (add_reviewer_verdict + adjudication)
--   4) eval_gold_label 表 + uk_gold 解耦(按 模型+样本 唯一)        (adjudication + gold_decouple)
--   5) eval_dataset.has_reference / has_model_response             (dataset_has_reference/model_response)
--   6) eval_article 表                                             (eval_article)
--   7) eval_prompt.version + eval_prompt_version 快照表            (prompt_versioning)
--   8) eval_task_prompt 冻结快照列 + 回填                          (prompt_versioning)
--   9) eval_task_summary.skip_count / good_count / unknown_count   (three_state_verdict)
--  10) eval_rss_source 表 + source_type 列                         (rss_source + rss_source_v2)
--  11) 预置 5 个订阅源（美团/腾讯云等，INSERT IGNORE 幂等）        (seed_rss_sources)
--  12) eval_task.judge_model_id                                    (早期手动批)
--  13) eval_gold_annotation 金标准标注表                            (migration_gold_annotation)
--  14) eval_user 平台用户表（登录/注册/鉴权）                       (migration_user_auth)
--  15) eval_dataset/model/prompt/task.created_by 数据归属+删除保护 (migration_user_ownership)
--
-- 每处 ALTER 都先查 information_schema：列/索引已存在即跳过。
-- 注意：MySQL 不支持 ADD COLUMN IF NOT EXISTS，必须用 PREPARE/EXECUTE 动态执行；
--      自定义变量(@s)赋值必须单行字符串、内部单引号用 '' 转义（勿用 || 拼接）。
--
-- 执行方式（在项目根目录运行；PowerShell 的 < 是保留字符，不能用引号外的 < 重定向）：
--   mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval -e "source llm-eval-server/sql/migration_patch_all.sql"
--   cmd /c "mysql --default-character-set=utf8mb4 -uroot -p1234 llm_eval < llm-eval-server\sql\migration_patch_all.sql"
-- ============================================================
SET NAMES utf8mb4;
SET @db = DATABASE();

-- ========== 1) 建表区（CREATE TABLE IF NOT EXISTS，全部幂等） ==========

-- 1.1 评测知识文章
CREATE TABLE IF NOT EXISTS eval_article (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL COMMENT '文章标题',
    source_name VARCHAR(200) COMMENT '来源团队/博客名',
    source_url VARCHAR(1000) NOT NULL COMMENT '原文链接（去重用）',
    author VARCHAR(200) COMMENT '作者',
    tags VARCHAR(500) COMMENT '标签，逗号分隔',
    summary TEXT COMMENT 'AI 生成摘要',
    content LONGTEXT COMMENT '文章正文（HTML 或纯文本）',
    published_at DATETIME COMMENT '原文发布时间',
    fetched_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    status TINYINT DEFAULT 1 COMMENT '状态 1-正常 0-隐藏',
    UNIQUE KEY uk_url (source_url(255)),
    INDEX idx_published (published_at DESC),
    INDEX idx_source (source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测知识文章';

-- 1.2 人工校验记录（AI vs 人工一致率）
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

-- 1.3 多角色人工校验判定（含 role，最新形态直接建含 role 版本）
CREATE TABLE IF NOT EXISTS eval_reviewer_verdict (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评测任务ID',
    model_config_id BIGINT NOT NULL COMMENT '被测模型ID',
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt ID',
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    reviewer VARCHAR(64) NOT NULL COMMENT '校验人',
    role VARCHAR(16) DEFAULT 'normal' COMMENT '校验人角色: normal-普通评委, expert-专家',
    is_badcase_human TINYINT DEFAULT NULL COMMENT '该校验人的判定: 1-badcase 0-非badcase',
    comment VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_review (task_id, model_config_id, prompt_id, dataset_item_id, reviewer)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多角色人工校验判定';

-- 1.4 金标准（最新形态：uk_gold 按 模型+样本 唯一，跨任务复用）
CREATE TABLE IF NOT EXISTS eval_gold_label (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT '评测任务ID',
    model_config_id BIGINT NOT NULL COMMENT '被测模型ID',
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt ID',
    dataset_item_id BIGINT NOT NULL COMMENT '数据集条目ID',
    is_badcase TINYINT NOT NULL COMMENT '金标准判定: 1-badcase 0-非badcase',
    has_disagreement TINYINT DEFAULT 0 COMMENT '评委是否有分歧: 1-有 0-无(一致)',
    adjudicator VARCHAR(64) COMMENT '裁决专家',
    adjudicate_comment VARCHAR(500) COMMENT '裁决备注',
    status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING-待裁决(有分歧), CONFIRMED-已确认',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_gold (model_config_id, dataset_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='金标准（专家裁决后的最终结论）';

-- 注意：旧库若已建 eval_gold_label（uk_gold 含 task_id），
-- 下面的 5.3/5.4 会自动把 uk_gold 重构为 (model_config_id, dataset_item_id)。

-- 1.5 评估器版本快照表
CREATE TABLE IF NOT EXISTS eval_prompt_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    prompt_id BIGINT NOT NULL COMMENT '关联评估器ID',
    version INT NOT NULL COMMENT '版本号',
    name VARCHAR(128) NOT NULL COMMENT 'Prompt名称',
    description VARCHAR(512) DEFAULT '' COMMENT '描述',
    prompt_template TEXT NOT NULL COMMENT '评测Prompt模板',
    dimensions_config TEXT NULL COMMENT '结构化维度配置JSON（含 strict_output）',
    evaluation_mode VARCHAR(16) DEFAULT 'quality' COMMENT 'quality/reference',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_prompt_id (prompt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估器版本快照';

-- 1.6 RSS 订阅源表（最新形态，直接含 source_type）
CREATE TABLE IF NOT EXISTS eval_rss_source (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(200) NOT NULL COMMENT '来源名称',
    feed_url VARCHAR(1000) NOT NULL COMMENT 'RSS/Atom 地址（tencent 类型时为腾讯云 JSON 接口地址）',
    source_type VARCHAR(20) NOT NULL DEFAULT 'rss' COMMENT '源类型：rss-标准feed，tencent-腾讯云JSON接口',
    description VARCHAR(500) COMMENT '描述',
    status TINYINT DEFAULT 1 COMMENT '1-启用 0-停用',
    last_fetched_at DATETIME COMMENT '上次拉取时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_feed_url (feed_url(255))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RSS 订阅源';

-- ========== 2) eval_task.judge_model_id（裁判模型，早期手台账） ==========
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task' AND COLUMN_NAME='judge_model_id');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task ADD COLUMN judge_model_id BIGINT NULL COMMENT ''裁判模型ID(NULL=使用第一个可用模型)'' AFTER dataset_id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ========== 3) eval_dataset 数据能力列 ==========
-- 3.1 has_reference
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_dataset' AND COLUMN_NAME='has_reference');
SET @s = IF(@c=0,
  'ALTER TABLE eval_dataset ADD COLUMN has_reference TINYINT DEFAULT 1 COMMENT ''是否含参考答案: 1-有(对照评测), 0-无(自由判断)'' AFTER column_mapping',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 3.2 has_model_response
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_dataset' AND COLUMN_NAME='has_model_response');
SET @s = IF(@c=0,
  'ALTER TABLE eval_dataset ADD COLUMN has_model_response TINYINT DEFAULT 0 COMMENT ''是否含模型结果: 1-有(评测直接读), 0-无(评测时调API生成)'' AFTER has_reference',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ========== 4) eval_judge_result 单维度判定 ==========
-- 4.1 dimension 列
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_judge_result' AND COLUMN_NAME='dimension');
SET @s = IF(@c=0,
  'ALTER TABLE eval_judge_result ADD COLUMN dimension VARCHAR(128) DEFAULT NULL COMMENT ''评测维度(NULL=整体判定记录，非NULL=单维度判定记录)'' AFTER prompt_id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4.2 idx_dimension 索引
SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_judge_result' AND INDEX_NAME='idx_dimension');
SET @s = IF(@c=0, 'ALTER TABLE eval_judge_result ADD INDEX idx_dimension (dimension)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 4.3 idx_badcase 索引（早期版本缺）
SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_judge_result' AND INDEX_NAME='idx_badcase');
SET @s = IF(@c=0, 'ALTER TABLE eval_judge_result ADD INDEX idx_badcase (is_badcase)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ========== 5) 多角色校验 + 金标准 ==========
-- 5.1 eval_reviewer_verdict.role 列（旧表无 role）
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_reviewer_verdict' AND COLUMN_NAME='role');
SET @s = IF(@c=0,
  'ALTER TABLE eval_reviewer_verdict ADD COLUMN role VARCHAR(16) DEFAULT ''normal'' COMMENT ''校验人角色: normal-普通评委, expert-专家'' AFTER reviewer',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5.2 金标准表已在上方 1.4 创建；若依赖 gold_decouple 语义，需处理 uk_gold。
-- 5.3 旧版 uk_gold 含 task_id → 重构为 (model_config_id, dataset_item_id)
SET @old = (SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_gold_label' AND INDEX_NAME='uk_gold' AND COLUMN_NAME='task_id');
SET @s = IF(@old>0,
  'ALTER TABLE eval_gold_label DROP INDEX uk_gold, ADD UNIQUE KEY uk_gold (model_config_id, dataset_item_id)',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 5.4 完全没有 uk_gold → 补建
SET @c = (SELECT COUNT(*) FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_gold_label' AND INDEX_NAME='uk_gold');
SET @s = IF(@c=0, 'ALTER TABLE eval_gold_label ADD UNIQUE KEY uk_gold (model_config_id, dataset_item_id)', 'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ========== 6) 评估器版本化 ==========
-- 6.1 eval_prompt.version
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_prompt' AND COLUMN_NAME='version');
SET @s = IF(@c=0,
  'ALTER TABLE eval_prompt ADD COLUMN version INT DEFAULT 1 COMMENT ''版本号（更新时自动+1）'' AFTER id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 6.2 往期评估器回填 version
UPDATE eval_prompt SET version = 1 WHERE version IS NULL OR version < 1;

-- ========== 7) eval_task_prompt 冻结快照列（逐个补，防半迁移状态） ==========
-- 7.1 prompt_version
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_prompt' AND COLUMN_NAME='prompt_version');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_prompt ADD COLUMN prompt_version INT DEFAULT NULL COMMENT ''创建任务时评估器版本快照'' AFTER prompt_id',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 7.2 prompt_name
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_prompt' AND COLUMN_NAME='prompt_name');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_prompt ADD COLUMN prompt_name VARCHAR(128) DEFAULT '''' COMMENT ''评估器名称快照'' AFTER prompt_version',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 7.3 prompt_template
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_prompt' AND COLUMN_NAME='prompt_template');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_prompt ADD COLUMN prompt_template TEXT NULL COMMENT ''评估Prompt模板快照'' AFTER prompt_name',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 7.4 dimensions_config
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_prompt' AND COLUMN_NAME='dimensions_config');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_prompt ADD COLUMN dimensions_config TEXT NULL COMMENT ''维度配置JSON快照（含 strict_output）'' AFTER prompt_template',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 7.5 历史任务回填快照（幂等：只填为空的行）
UPDATE eval_task_prompt tp
    JOIN eval_prompt p ON p.id = tp.prompt_id
SET tp.prompt_version   = IFNULL(p.version, 1),
    tp.prompt_name      = IFNULL(p.name, ''),
    tp.prompt_template  = IFNULL(p.prompt_template, ''),
    tp.dimensions_config = IFNULL(p.dimensions_config, '')
WHERE tp.prompt_template IS NULL OR tp.prompt_template = '';

-- ========== 8) 最终判定三态（goodcase / badcase / unknown） ==========
-- 8.1 skip_count（早期版本缺；必须先于 good_count 存在）
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_summary' AND COLUMN_NAME='skip_count');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_summary ADD COLUMN skip_count INT DEFAULT 0 COMMENT ''无法判定数(AI输出无法解析)'' AFTER badcase_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 8.2 good_count
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_summary' AND COLUMN_NAME='good_count');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_summary ADD COLUMN good_count INT DEFAULT 0 COMMENT ''goodcase数量'' AFTER skip_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 8.3 unknown_count
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task_summary' AND COLUMN_NAME='unknown_count');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task_summary ADD COLUMN unknown_count INT DEFAULT 0 COMMENT ''unknown数量(AI无法判定结论)'' AFTER good_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 8.4 历史汇总回填（good_count 只回填一次，之后以实际统计为准）
UPDATE eval_task_summary
SET good_count = total_count - badcase_count,
    unknown_count = 0
WHERE good_count IS NULL OR good_count = 0;

-- ========== 9) 订阅源类型 v2（支持腾讯云 JSON 接口源） ==========
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_rss_source' AND COLUMN_NAME='source_type');
SET @s = IF(@c=0,
  'ALTER TABLE eval_rss_source ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT ''rss'' COMMENT ''源类型：rss-标准feed，tencent-腾讯云JSON接口'' AFTER feed_url',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 历史行默认 rss
UPDATE eval_rss_source SET source_type = 'rss' WHERE source_type IS NULL OR source_type = '';

-- ========== 10) 预置订阅源（INSERT IGNORE 幂等，按 feed_url 去重） ==========
INSERT IGNORE INTO eval_rss_source (source_name, feed_url, source_type, description, status) VALUES
('InfoQ 中文', 'https://www.infoq.cn/feed', 'rss', '软件开发与 AI/Agent 实践资讯，RSS 2.0', 1),
('阮一峰 · 科技爱好者周刊', 'https://www.ruanyifeng.com/blog/atom.xml', 'rss', '每周科技 + AI 大摘要，Atom 格式，含正文', 1),
('Hugging Face Blog', 'https://huggingface.co/blog/feed.xml', 'rss', '开源模型与 AI 研究博客（RSS 无正文，走全文兜底抓取）', 1),
('美团技术团队', 'https://tech.meituan.com/feed/', 'rss', '美团官方 RSS feed，含全文(content:encoded)，AI/Agent 与后端内容居多', 1),
('腾讯云开发者社区 · 全部专栏', 'https://cloud.tencent.com/developer/api/home/article-list?classifyId=0', 'tencent', '腾讯云开发者社区专栏文章（JSON 接口，classifyId=0 表示全部）', 1);

-- ========== 11) 异步预分析任务表（AI识别/润色/重复检测） ==========
CREATE TABLE IF NOT EXISTS eval_async_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_type VARCHAR(30) NOT NULL COMMENT '任务类型: PARSE_DIMENSIONS/POLISH/DETECT_DUPLICATES',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED',
    progress INT DEFAULT 0 COMMENT '进度 0-100',
    progress_text VARCHAR(255) DEFAULT '' COMMENT '进度描述（如 正在润色维度 2/5）',
    payload TEXT COMMENT '请求参数JSON',
    result_data LONGTEXT NULL COMMENT '结果JSON',
    error_message TEXT NULL COMMENT '失败信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    INDEX idx_type_status (job_type, status),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步预分析任务（AI识别/润色/重复检测）';

-- ========== 12) 金标准标注台（条目级多标注者投片，脱离任务/模型） ==========
-- 与 eval_gold_label（按 model_config_id+dataset_item_id 挂模型裁决结论）不同，
-- 本表按 (dataset_item_id, annotator) 存多位标注者对条目的独立判定，做一致性统计。
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

-- ========== 14) 平台用户表（登录/注册/鉴权），首个注册用户由代码赋予 ADMIN ==========
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

-- ========== 15) 数据归属 + 删除保护（created_by） ==========
-- 四张核心用户数据表补创建者ID；NULL=历史无归属数据（仅管理员可删）。
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_dataset' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_dataset ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER total_count',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_model_config' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_model_config ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER status',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_prompt' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_prompt ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)'' AFTER status',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_task' AND COLUMN_NAME='created_by');
SET @s = IF(@c=0,
  'ALTER TABLE eval_task ADD COLUMN created_by BIGINT NULL COMMENT ''创建者ID(eval_user.id)，NULL=历史无归属数据'' AFTER progress',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 16) eval_model_config 软删除字段
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=@db AND TABLE_NAME='eval_model_config' AND COLUMN_NAME='is_deleted');
SET @s = IF(@c=0,
  'ALTER TABLE eval_model_config ADD COLUMN is_deleted TINYINT DEFAULT 0 COMMENT ''软删除: 0-正常 1-已删除'' AFTER created_by',
  'SELECT 1');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- ========== 完成 ==========
SELECT 'migration_patch_all 执行完毕：结构已追平最新代码（含 15 批迁移）。' AS result;