package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.AdjudicationDTO;
import com.eval.model.dto.HumanReviewDTO;
import com.eval.model.entity.EvalJudgeResult;
import com.eval.model.entity.EvalResult;
import com.eval.model.entity.EvalTaskSummary;
import com.eval.model.vo.BadcaseVO;
import com.eval.model.vo.HumanReviewStatsVO;
import com.eval.model.vo.HumanVsAiStatsVO;

import java.util.List;

public interface EvalResultService {

    PageResult<EvalResult> list(Long taskId, Long modelConfigId, int page, int size);

    List<EvalResult> compare(Long taskId);

    /** 增强模型对比：按样本分组，展示各模型回答 + AI 判定 */
    List<com.eval.model.vo.ModelCompareVO> compareWithJudge(Long taskId);

    List<EvalTaskSummary> getSummary(Long taskId);

    PageResult<BadcaseVO> listBadcases(Long taskId, Long promptId, Long modelConfigId,
                                       String dimension, String keyword, int page, int size);

    List<BadcaseVO> listJudgeResults(Long taskId, Long promptId, Long modelConfigId);

    /** 维度级别判定记录（每条含 dimension + reason） */
    List<BadcaseVO> listDimensionResults(Long taskId, Long modelConfigId);

    String generateReport(Long taskId);

    // ===== 链路一：人工标注（普通评委 + 专家裁决 → 金标准） =====

    /** 普通评委/专家提交判定（含角色） */
    void submitHumanReview(HumanReviewDTO dto);

    /** 获取人工标注统计（人机一致率 + 人人一致率 + Kappa） */
    HumanReviewStatsVO getHumanReviewStats(Long taskId, Long modelConfigId, Long promptId);

    /** 待人工校验的样本列表（含AI判定 + 已有人工判定，按 reviewer 权限过滤） */
    List<BadcaseVO> listReviewSamples(Long taskId, Long promptId, Long modelConfigId,
                                       String reviewer, String role, int page, int size);

    /** 待专家裁决的样本列表（有分歧的样本） */
    List<BadcaseVO> listAdjudicationSamples(Long taskId, Long promptId, Long modelConfigId, int page, int size);

    /** 专家裁决：对分歧样本做最终判定，写入金标准（数据集级别，跨任务复用） */
    void adjudicate(AdjudicationDTO dto);

    // ===== 链路二：人机对比（AI vs 金标准 → 不一致率） =====

    /** 人机对比统计：AI 判定 vs 金标准的不一致率 */
    HumanVsAiStatsVO getHumanVsAiStats(Long taskId, Long modelConfigId, Long promptId);

    /** 人机对比样本列表（AI 判定 + 金标准 + 是否一致） */
    List<BadcaseVO> listHumanVsAiSamples(Long taskId, Long promptId, Long modelConfigId, int page, int size);
}
