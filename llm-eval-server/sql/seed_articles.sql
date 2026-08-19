-- ============================================================
-- 评测知识 · 种子文章（可选执行，已有环境升级时用）
-- 使用 INSERT IGNORE，按 source_url 唯一键去重，可重复执行
-- ============================================================
-- 必须：连接字符集 utf8mb4，否则中文会乱码
SET NAMES utf8mb4;

-- 1. Anthropic《Building effective agents》—— Agent 设计 + 评测驱动开发
INSERT IGNORE INTO eval_article (title, source_name, source_url, author, tags, summary, content, published_at, status)
SELECT
  'Building Effective Agents：Anthropic 的 Agent 构建与评测实践',
  'Anthropic Engineering',
  'https://www.anthropic.com/engineering/building-effective-agents',
  'Anthropic',
  'agent,评测,LLM,workflow',
  'Anthropic 官方工程博客：介绍构建高效 Agent 的核心方法论，强调"评测驱动开发"——先用良好定义的评测集衡量改动效果，再迭代优化，并给出简单的评分器（grader）示例。',
  CONCAT(
    '【本文为平台整理的精炼版，原文见“查看原文”链接】\n\n',
    'Anthropic 在本文中系统阐述了如何构建可靠、高效的 AI Agent。核心观点整理如下：\n\n',
    '一、Workflow 与 Agent 的区别\n',
    'Workflow 是预定义的代码路径（prompt 链、Router、并行化等），按固定流程执行多次调用；Agent 则动态决定自身的执行路径，自主调用工具、维护上下文、根据过程反馈调整下一步。构建系统前应先问：满足延时与成本的前提下，最简单的方案是什么？多数场景用增强型 workflow 即可，盲目升级到 Agent 会引入不确定性与成本。\n\n',
    '二、Agent 核心能力：工具与上下文\n',
    'Agent 的关键设计决策是让模型能够稳定调用工具：工具命名与描述要清晰，参数类型要严格，用充足的工具返回信息（metadata、错误信息）辅助模型自我纠错。上下文工程（context engineering）同样重要，如压缩长上下文、结构化原生输出、区分 system/user。\n\n',
    '三、评测驱动开发（Evaluations driven development）\n',
    '这是本文最相关的部分：不断迭代 Agent 需要一套好的评测（evals）来度量改动是变好还是变坏。具体做法：(1) 定义一组覆盖典型与边缘场景的评测用例；(2) 实现简单的 LLM-as-judge 评分器，对 Agent 输出按维度打分；(3) 每次修改后跑评测对比，用数据而非直觉决定去留；(4) 先离线评测（自动+人工抽查），再灰度上线看线上指标。作者强调：先完善评测集，再谈优化 Agent——没有评测的迭代只是盲改。\n\n',
    '四、结论\n',
    '成功的 Agent 系统 = 聚焦的 tool 设计 + 良好的上下文管理 + 评测驱动的持续迭代。评测不是可选项，而是 Agent 从 Demo 走向稳定的基础设施。'
  ),
  '2025-12-19 00:00:00',
  1
;

-- 2. InfoQ《DeepSeek Harness 怎么入门》—— 评测框架实战教程
INSERT IGNORE INTO eval_article (title, source_name, source_url, author, tags, summary, content, published_at, status)
SELECT
  'DeepSeek Harness 怎么入门？一套极简评测框架教程',
  'InfoQ',
  'https://www.infoq.cn/article/1EP0hwuqRzccrWArR7GJ',
  'Sean',
  '评测,harness,LLM,benchmark',
  'InfoQ 技术教程：以简洁的方式介绍 DeepSeek Harness 评测框架，用最小的代码量完成模型评测任务落地，是快速上手 LLM 评测基础设施的入门材料。',
  CONCAT(
    '【本文为平台整理的精炼版，原文见“查看原文”链接】\n\n',
    '评测 Harness 是连接「模型」与「任务」的测试台架：给定一组数据与打分规则，用统一方式跑模型、收输出、算指标。本文以 DeepSeek Harness 为例讲解入门路径：\n\n',
    '- 安装与初始化：通过 pip 安装 harness，ready-to-use 的子命令即可拉起评测；\n',
    '- 数据集适配：内置常见数据集格式，也支持自定义 JSON/CSV，把「问题-参考-模型输出」组织成统一样本；\n',
    '- 打分：内置多种 metric（准确率、rouge、bert-score 等），也支持 LLM-as-judge 场景；\n',
    '- 批量跑批与结果汇总：一次配置多种模型跑同样的评测集，产出对比报告。\n\n',
    '对本平台的启发：评测平台的核心也是「样本 + 指标 + 判定」三段式，Harness 类工具把这三个环节标准化，正是 Agent/LLM 评测从临时脚本走向工程化的关键。'
  ),
  '2026-08-18 12:43:32',
  1
;

-- 3. 美团技术团队 —— Agent 评测 Rubric 二元化方法论（goodcase / badcase / unknown）
INSERT IGNORE INTO eval_article (title, source_name, source_url, author, tags, summary, content, published_at, status)
SELECT
  'Agent 评测方法论：Rubric 二元化判定 goodcase / badcase / unknown',
  '美团技术团队',
  'https://tech.meituan.com/',
  '美团技术团队',
  '评测,rubric,badcase,agent,方法论',
  '美团在智能体评测实践中采用的 Rubric 方法论：把复杂评分项拆成可被 LLM 逐条判定的二元标准（是/否），对资料不充分的情况引入第三态 unknown，显著提升判定一致性与可解释性，是当前业界有代表性的 Agent 评测范式。',
  CONCAT(
    '【本文为平台整理的精炼版，对应美团技术团队公开分享的 Rubric 方法论，原文入口见“查看原文”链接】\n\n',
    '一、为什么不用传统打分\n',
    '给 Agent 回复打 0-5 分看似合理，但不同裁判模型、不同 prompt 下分数漂移严重，且"为什么扣 1 分"不可解释。Rubric 方法论把模糊的评分拆成一条条可执行的判定标准：每条标准是一个二元问题，AI 只需回答「是否满足」，结果稳定且可追溯。\n\n',
    '二、Rubric 判定三态\n',
    '每条 Rubric 标准的判定结果为三态：\n',
    '- goodcase（符合/通过）：模型行为满足该标准；\n',
    '- badcase（违反/不通过）：模型行为违反该标准，触发整改或标记；\n',
    '- unknown（无法判断）：给定信息不足以做可靠判定，宁可标记 unknown 也不强行下结论，避免误杀或漏判。\n\n',
    '三、落地要点\n',
    '1. 判定信息不足时输出 unknown 而不是猜一个值；\n',
    '2. 整体结论由各 Rubric 项聚合：任一项 badcase → 整体 badcase；无 badcase 但存在 unknown → 整体 unknown；否则 goodcase；\n',
    '3. 用「人工抽检 vs 机器判定」的一致性持续校准 Rubric 措辞。\n\n',
    '四、对本平台的意义\n',
    '本平台的最终判定三态（goodcase/badcase/unknown）正是对齐该方法论：布尔维度可输出 unknown，聚合时「有 badcase 判 bad、有 unknown 判 unknown、否则判 good」，与美团思路一致。'
  ),
  '2024-12-01 00:00:00',
  1
;