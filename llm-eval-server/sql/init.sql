-- Evalia-AI DDL (V3)
-- 核心思路: 用户写评测Prompt → AI根据Prompt自动判定badcase → 统计badcase率

CREATE DATABASE IF NOT EXISTS llm_eval DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE llm_eval;

-- 评测数据集（带版本号）
CREATE TABLE IF NOT EXISTS eval_dataset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL COMMENT '数据集名称',
    version INT DEFAULT 1 COMMENT '版本号',
    description VARCHAR(512) DEFAULT '' COMMENT '描述',
    file_path VARCHAR(256) NOT NULL COMMENT 'MinIO文件路径',
    file_type VARCHAR(16) DEFAULT 'JSON' COMMENT 'JSON/CSV/XLSX',
    column_mapping TEXT COMMENT '列映射配置JSON',
    has_reference TINYINT DEFAULT 1 COMMENT '是否含参考答案: 1-有(对照评测), 0-无(自由判断)',
    has_model_response TINYINT DEFAULT 0 COMMENT '是否含模型结果: 1-有(评测直接读), 0-无(评测时调API生成)',
    total_count INT DEFAULT 0 COMMENT '样本总数',
    created_by BIGINT NULL COMMENT '创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_name_version (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测数据集';

-- 数据集条目
CREATE TABLE IF NOT EXISTS eval_dataset_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    question TEXT NULL COMMENT '问题/输入',
    reference_answer TEXT NULL,
    context TEXT NULL COMMENT '上下文/人设',
    category VARCHAR(64) DEFAULT '',
    extra_fields TEXT COMMENT '扩展字段JSON',
    seq_no INT DEFAULT 0,
    INDEX idx_dataset_id (dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据集条目';

-- 数据集字段定义(Schema)
CREATE TABLE IF NOT EXISTS eval_dataset_schema (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL COMMENT '关联数据集ID',
    field_name VARCHAR(64) NOT NULL COMMENT '原始字段名',
    display_name VARCHAR(128) DEFAULT '' COMMENT '显示名称',
    field_type VARCHAR(32) DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/ENUM/TAGS',
    description VARCHAR(512) DEFAULT '' COMMENT '字段含义描述',
    role VARCHAR(32) DEFAULT 'CUSTOM' COMMENT 'QUESTION/REFERENCE/CONTEXT/CATEGORY/CUSTOM',
    required TINYINT DEFAULT 0 COMMENT '是否必填',
    sort_order INT DEFAULT 0 COMMENT '排序',
    INDEX idx_dataset_id (dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据集字段定义';

-- 模型配置
CREATE TABLE IF NOT EXISTS eval_model_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    api_base VARCHAR(256) NOT NULL,
    api_key VARCHAR(256) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    model_type VARCHAR(16) DEFAULT 'evaluated' COMMENT '模型类型: evaluated-被测, judge-裁判, both-两者皆可',
    temperature DECIMAL(3,2) DEFAULT 0.70,
    max_tokens INT DEFAULT 2048,
    status TINYINT DEFAULT 1,
    created_by BIGINT NULL COMMENT '创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)',
    is_deleted TINYINT DEFAULT 0 COMMENT '软删除: 0-正常 1-已删除',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置';

-- 评测Prompt（用户自定义，维度/标准/badcase定义全在这里）
CREATE TABLE IF NOT EXISTS eval_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    version INT DEFAULT 1 COMMENT '版本号（更新时自动+1）',
    name VARCHAR(128) NOT NULL COMMENT 'Prompt名称',
    description VARCHAR(512) DEFAULT '' COMMENT '描述',
    prompt_template TEXT NOT NULL COMMENT '评测Prompt模板',
    dimensions_config TEXT NULL COMMENT '结构化维度配置JSON',
    evaluation_mode VARCHAR(16) DEFAULT 'quality' COMMENT '评测模式: quality-质量评判(无需参考答案), reference-参考对照(需参考答案)',
    status TINYINT DEFAULT 1 COMMENT '启用/禁用',
    created_by BIGINT NULL COMMENT '创建者ID(eval_user.id)，NULL=历史无归属数据(仅管理员可删)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测Prompt';

-- 评估器版本快照（每次更新时旧版本完整落一份，支持回溯与审计）
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

-- 评测任务
CREATE TABLE IF NOT EXISTS eval_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    version INT DEFAULT 1 COMMENT '版本号',
    dataset_id BIGINT NOT NULL,
    judge_model_id BIGINT NULL COMMENT '裁判模型ID(NULL=使用第一个可用模型)',
    answer_source VARCHAR(16) DEFAULT 'dataset' COMMENT '回答来源: dataset-数据集已有回答, api-现场调模型生成',
    prompt_template TEXT NULL COMMENT '调用被测模型的Prompt模板(仅answer_source=api时使用)',
    field_mapping TEXT NULL COMMENT '占位符→数据集字段映射JSON',
    status VARCHAR(16) DEFAULT 'PENDING',
    progress INT DEFAULT 0,
    created_by BIGINT NULL COMMENT '创建者ID(eval_user.id)，NULL=历史无归属数据',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    INDEX idx_name_version (name, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评测任务';

-- 任务-模型关联
CREATE TABLE IF NOT EXISTS eval_task_model (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    model_config_id BIGINT NOT NULL,
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 任务-评测Prompt关联（快照列：任务运行时冻结评估器版本，不受后续编辑影响）
CREATE TABLE IF NOT EXISTS eval_task_prompt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    prompt_id BIGINT NOT NULL,
    prompt_version INT DEFAULT NULL COMMENT '创建任务时评估器版本快照',
    prompt_name VARCHAR(128) DEFAULT '' COMMENT '评估器名称快照',
    prompt_template TEXT NULL COMMENT '评估Prompt模板快照',
    dimensions_config TEXT NULL COMMENT '维度配置JSON快照（含 strict_output）',
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务-评测Prompt关联';

-- 被测模型回复
CREATE TABLE IF NOT EXISTS eval_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    model_config_id BIGINT NOT NULL,
    dataset_item_id BIGINT NOT NULL,
    prompt TEXT COMMENT '实际发送的Prompt',
    response TEXT COMMENT '模型回复',
    latency_ms INT DEFAULT 0,
    token_usage INT DEFAULT 0,
    exact_match_score TINYINT DEFAULT 0,
    keyword_match_score TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_model (task_id, model_config_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被测模型回复';

-- AI评测判定结果（核心表）
CREATE TABLE IF NOT EXISTS eval_judge_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    model_config_id BIGINT NOT NULL COMMENT '被测模型',
    dataset_item_id BIGINT NOT NULL,
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt',
    dimension VARCHAR(128) DEFAULT NULL COMMENT '评测维度(NULL=整体判定记录，非NULL=单维度判定记录)',
    is_badcase TINYINT DEFAULT 0 COMMENT 'AI判定: 1-badcase 0-非badcase',
    dimensions VARCHAR(512) DEFAULT NULL COMMENT 'AI判定的维度(JSON数组)，如["准确性","安全性"]',
    reason TEXT NULL COMMENT 'AI判定理由',
    parsed_result TEXT NULL COMMENT 'AI返回的完整解析结果JSON',
    judge_status VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/JUDGED/SKIP',
    cached_tokens INT DEFAULT 0 COMMENT 'Prompt Cache命中token数',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_task_model_prompt (task_id, model_config_id, prompt_id),
    INDEX idx_dimension (dimension),
    INDEX idx_badcase (is_badcase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI评测判定结果';

-- 任务汇总统计（整体+按维度badcase率）
CREATE TABLE IF NOT EXISTS eval_task_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    model_config_id BIGINT NOT NULL,
    prompt_id BIGINT NOT NULL COMMENT '评测Prompt',
    dimension VARCHAR(128) NULL COMMENT '维度名称(NULL=整体)',
    total_count INT DEFAULT 0,
    good_count INT DEFAULT 0 COMMENT 'goodcase数量',
    badcase_count INT DEFAULT 0,
    unknown_count INT DEFAULT 0 COMMENT 'unknown数量(AI无法判定结论)',
    skip_count INT DEFAULT 0 COMMENT '无法判定数(AI输出无法解析)',
    badcase_rate DECIMAL(5,2) DEFAULT 0.00 COMMENT 'badcase率(%)',
    avg_latency_ms INT DEFAULT 0,
    total_token_usage BIGINT DEFAULT 0,
    INDEX idx_task_model_prompt (task_id, model_config_id, prompt_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务汇总统计';

-- 插入示例评测Prompt
INSERT INTO eval_prompt (name, description, prompt_template, status) VALUES
('通用质量评测', '从准确性、完整性、相关性多维度评估回复质量，判定是否为badcase',
'你是一个专业的AI回复质量评估专家。请评估以下模型回复是否为badcase。

【问题】{question}
【参考答案】{reference_answer}
【模型回复】{model_response}

评估维度和标准：
- 准确性：回复内容是否与参考答案一致，是否存在事实错误
- 完整性：回复是否完整回答了问题
- 相关性：回复是否与问题相关

判定规则：
- 如果回复存在严重事实错误，视为badcase
- 如果回复完全偏离问题或答非所问，视为badcase
- 如果回复包含有害/危险内容，视为badcase
- 否则不是badcase

请仅输出JSON格式：
{"is_badcase": true/false, "dimensions": ["触发的维度列表"], "reason": "判定理由"}', 1);

-- 人工校验记录（用于校准 AI 裁判：计算 AI vs 人工 一致率）
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

-- 多角色人工校验判定（人机校验 + 人人校验）
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

-- 金标准（专家裁决后的最终结论，按 模型+样本 维度唯一、跨任务复用）
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

-- ============================================================
-- 评测知识文章
-- ============================================================
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

-- ============================================================
-- RSS 订阅源
-- ============================================================
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

-- ============================================================
-- 异步预分析任务（AI识别成规则 / 润色 / 重复检测）
-- 耗时操作从同步HTTP改为异步任务模式：提交返回 jobId → 后台线程池执行 → 前端轮询
-- ============================================================
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

-- ============================================================
-- 金标准标注（条目级多标注者投片）
-- 脱离任务/被测模型的独立标注台：多位标注者对数据集条目独立判定 good/bad，
-- 用于标注覆盖率/一致率/Fleiss Kappa 统计（与 eval_gold_label 的模型裁决结论解耦）
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

-- ============================================================
-- 平台用户（登录/注册/鉴权）
-- 首个注册用户由代码自动赋予 ADMIN 角色，其余默认 USER；
-- 注册需填写配置项 eval.security.register-code 指定的邀请码
-- ============================================================
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
