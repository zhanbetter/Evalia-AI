package com.eval.web.controller;

import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.AdjudicationDTO;
import com.eval.model.dto.HumanReviewDTO;
import com.eval.model.entity.EvalJudgeResult;
import com.eval.model.entity.EvalResult;
import com.eval.model.entity.EvalTaskSummary;
import com.eval.model.vo.BadcaseVO;
import com.eval.model.vo.HumanReviewStatsVO;
import com.eval.model.vo.HumanVsAiStatsVO;
import com.eval.service.EvalResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/results")
@RequiredArgsConstructor
public class EvalResultController {

    private final EvalResultService evalResultService;

    @GetMapping
    public Result<PageResult<EvalResult>> list(
            @RequestParam(required = false) Long taskId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(evalResultService.list(taskId, modelConfigId, page, size));
    }

    @GetMapping("/compare")
    public Result<List<EvalResult>> compare(@RequestParam Long taskId) {
        return Result.success(evalResultService.compare(taskId));
    }

    @GetMapping("/compare-with-judge")
    public Result<List<com.eval.model.vo.ModelCompareVO>> compareWithJudge(@RequestParam Long taskId) {
        return Result.success(evalResultService.compareWithJudge(taskId));
    }

    @GetMapping("/{taskId}/summary")
    public Result<List<EvalTaskSummary>> getSummary(@PathVariable Long taskId) {
        return Result.success(evalResultService.getSummary(taskId));
    }

    @GetMapping("/{taskId}/badcases")
    public Result<PageResult<BadcaseVO>> listBadcases(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long promptId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(required = false) String dimension,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(evalResultService.listBadcases(taskId, promptId, modelConfigId, dimension, keyword, page, size));
    }

    @GetMapping("/{taskId}/judge-results")
    public Result<List<BadcaseVO>> listJudgeResults(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long promptId,
            @RequestParam(required = false) Long modelConfigId) {
        return Result.success(evalResultService.listJudgeResults(taskId, promptId, modelConfigId));
    }

    @GetMapping("/{taskId}/dimension-results")
    public Result<List<BadcaseVO>> listDimensionResults(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long modelConfigId) {
        return Result.success(evalResultService.listDimensionResults(taskId, modelConfigId));
    }

    // ===== 人工校验 =====

    @PostMapping("/human-review")
    public Result<Void> submitHumanReview(@Valid @RequestBody HumanReviewDTO dto) {
        evalResultService.submitHumanReview(dto);
        return Result.success();
    }

    @GetMapping("/{taskId}/human-review/stats")
    public Result<HumanReviewStatsVO> getHumanReviewStats(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(required = false) Long promptId) {
        return Result.success(evalResultService.getHumanReviewStats(taskId, modelConfigId, promptId));
    }

    @GetMapping("/{taskId}/human-review/samples")
    public Result<List<BadcaseVO>> listReviewSamples(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long promptId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(required = false) String reviewer,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(evalResultService.listReviewSamples(taskId, promptId, modelConfigId, reviewer, role, page, size));
    }

    // ===== 专家裁决 =====

    @GetMapping("/{taskId}/adjudication/samples")
    public Result<List<BadcaseVO>> listAdjudicationSamples(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long promptId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(evalResultService.listAdjudicationSamples(taskId, promptId, modelConfigId, page, size));
    }

    @PostMapping("/adjudicate")
    public Result<Void> adjudicate(@Valid @RequestBody AdjudicationDTO dto) {
        evalResultService.adjudicate(dto);
        return Result.success();
    }

    // ===== 人机对比 =====

    @GetMapping("/{taskId}/human-vs-ai/stats")
    public Result<HumanVsAiStatsVO> getHumanVsAiStats(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(required = false) Long promptId) {
        return Result.success(evalResultService.getHumanVsAiStats(taskId, modelConfigId, promptId));
    }

    @GetMapping("/{taskId}/human-vs-ai/samples")
    public Result<List<BadcaseVO>> listHumanVsAiSamples(
            @PathVariable Long taskId,
            @RequestParam(required = false) Long promptId,
            @RequestParam(required = false) Long modelConfigId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(evalResultService.listHumanVsAiSamples(taskId, promptId, modelConfigId, page, size));
    }
}
