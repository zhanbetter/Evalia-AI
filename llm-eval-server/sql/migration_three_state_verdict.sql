-- ============================================================
-- Migration: 最终判定三态 goodcase / badcase / unknown
-- 1) eval_task_summary 增加 good_count / unknown_count 列
--    - total_count  = good_count + badcase_count + unknown_count（判定成功总数）
--    - skip_count   保持原义：AI输出无法解析/异常 标记 SKIP 的记录数
--    - is_badcase 扩义：1=badcase, 0=goodcase, null=unknown
--      （judge_status=JUDGED 且 is_badcase=null 即 AI 自报 unknown；
--        judge_status=SKIP 仍为解析失败）
-- 2) 老数据回填：unknown=0，good = total - badcase
-- ============================================================

ALTER TABLE eval_task_summary
  ADD COLUMN good_count INT DEFAULT 0 COMMENT 'goodcase数量' AFTER badcase_count,
  ADD COLUMN unknown_count INT DEFAULT 0 COMMENT 'unknown数量(AI无法判定结论)' AFTER skip_count;

-- 回填历史数据（老版本无 unknown，good 直接由 total - badcase 得出）
UPDATE eval_task_summary
SET good_count = total_count - badcase_count,
    unknown_count = 0
WHERE good_count IS NULL OR good_count = 0;