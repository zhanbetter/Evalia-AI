package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.*;
import com.eval.model.dto.AdjudicationDTO;
import com.eval.model.dto.HumanReviewDTO;
import com.eval.model.entity.*;
import com.eval.model.vo.BadcaseVO;
import com.eval.model.vo.HumanReviewStatsVO;
import com.eval.model.vo.HumanVsAiStatsVO;
import com.eval.service.EvalResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalResultServiceImpl implements EvalResultService {

    private final EvalResultMapper resultMapper;
    private final EvalTaskSummaryMapper summaryMapper;
    private final EvalJudgeResultMapper judgeResultMapper;
    private final EvalTaskMapper taskMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final EvalTaskPromptMapper taskPromptMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final EvalPromptMapper promptMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final EvalHumanReviewMapper humanReviewMapper;
    private final EvalReviewerVerdictMapper reviewerVerdictMapper;
    private final EvalGoldLabelMapper goldLabelMapper;

    @Override
    public PageResult<EvalResult> list(Long taskId, Long modelConfigId, int page, int size) {
        Page<EvalResult> pageObj = new Page<>(page, size);
        Page<EvalResult> result = resultMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalResult>()
                        .eq(taskId != null, EvalResult::getTaskId, taskId)
                        .eq(modelConfigId != null, EvalResult::getModelConfigId, modelConfigId)
                        .orderByAsc(EvalResult::getId));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public List<EvalResult> compare(Long taskId) {
        return resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, taskId)
                .orderByAsc(EvalResult::getDatasetItemId, EvalResult::getModelConfigId));
    }

    @Override
    public List<com.eval.model.vo.ModelCompareVO> compareWithJudge(Long taskId) {
        // 1. 所有评测结果
        List<EvalResult> results = resultMapper.selectList(new LambdaQueryWrapper<EvalResult>()
                .eq(EvalResult::getTaskId, taskId)
                .orderByAsc(EvalResult::getDatasetItemId, EvalResult::getModelConfigId));

        // 2. 所有裁判判定（整体记录）
        List<EvalJudgeResult> judges = judgeResultMapper.selectList(new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .isNull(EvalJudgeResult::getDimension));

        // 3. 模型名映射
        Set<Long> modelIds = results.stream().map(EvalResult::getModelConfigId).collect(Collectors.toSet());
        Map<Long, String> modelNameMap = modelIds.isEmpty() ? Map.of()
                : modelConfigMapper.selectBatchIds(modelIds).stream()
                        .collect(Collectors.toMap(EvalModelConfig::getId, EvalModelConfig::getName, (a, b) -> a));

        // 4. 数据集条目映射（question/reference/context）
        Set<Long> itemIds = results.stream().map(EvalResult::getDatasetItemId).collect(Collectors.toSet());
        Map<Long, EvalDatasetItem> itemMap = itemIds.isEmpty() ? Map.of()
                : datasetItemMapper.selectBatchIds(itemIds).stream()
                        .collect(Collectors.toMap(EvalDatasetItem::getId, i -> i, (a, b) -> a));

        // 5. judge 索引: (modelConfigId + "_" + datasetItemId) -> EvalJudgeResult
        Map<String, EvalJudgeResult> judgeMap = judges.stream()
                .collect(Collectors.toMap(
                        j -> j.getModelConfigId() + "_" + j.getDatasetItemId(),
                        j -> j, (a, b) -> a));

        // 6. 按数据集条目分组
        Map<Long, com.eval.model.vo.ModelCompareVO> groupMap = new LinkedHashMap<>();
        for (EvalResult r : results) {
            com.eval.model.vo.ModelCompareVO vo = groupMap.computeIfAbsent(r.getDatasetItemId(),
                    k -> new com.eval.model.vo.ModelCompareVO());
            EvalDatasetItem item = itemMap.get(r.getDatasetItemId());
            if (vo.getQuestion() == null) {
                vo.setDatasetItemId(r.getDatasetItemId());
                vo.setQuestion(item != null ? item.getQuestion() : "");
                vo.setReferenceAnswer(item != null ? item.getReferenceAnswer() : "");
                vo.setContext(item != null ? item.getContext() : "");
            }

            com.eval.model.vo.ModelCompareVO.ModelAnswer answer = new com.eval.model.vo.ModelCompareVO.ModelAnswer();
            answer.setModelConfigId(r.getModelConfigId());
            answer.setModelName(modelNameMap.getOrDefault(r.getModelConfigId(), "模型" + r.getModelConfigId()));
            answer.setResponse(r.getResponse());
            answer.setLatencyMs(r.getLatencyMs());

            EvalJudgeResult judge = judgeMap.get(r.getModelConfigId() + "_" + r.getDatasetItemId());
            if (judge != null) {
                answer.setIsBadcase(judge.getIsBadcase());
                answer.setJudgeReason(judge.getReason());
            }
            vo.getAnswers().add(answer);
        }

        return new ArrayList<>(groupMap.values());
    }

    @Override
    public List<EvalTaskSummary> getSummary(Long taskId) {
        return summaryMapper.selectList(new LambdaQueryWrapper<EvalTaskSummary>()
                .eq(EvalTaskSummary::getTaskId, taskId)
                .orderByAsc(EvalTaskSummary::getDimension));
    }

    @Override
    public PageResult<BadcaseVO> listBadcases(Long taskId, Long promptId, Long modelConfigId,
                                              String dimension, String keyword, int page, int size) {
        LambdaQueryWrapper<EvalJudgeResult> wrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                // 只取整体判定记录（单维度模式下维度记录不重复展示）
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getIsBadcase, 1)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId);

        // 维度筛选：dimensions 字段存储的是 JSON 数组如 ["准确性","安全性"]，用 LIKE 匹配维度名
        if (StrUtil.isNotBlank(dimension)) {
            wrapper.like(EvalJudgeResult::getDimensions, dimension);
        }

        boolean needKeywordFilter = StrUtil.isNotBlank(keyword);

        if (needKeywordFilter) {
            // 有关键词：DB层粗过滤 reason/dimensions/question，减少数据量
            // question 通过子查询关联 eval_dataset_item 过滤
            Set<Long> matchingItemIds = datasetItemMapper.selectList(
                    new LambdaQueryWrapper<EvalDatasetItem>()
                            .eq(EvalDatasetItem::getDatasetId, getDatasetId(taskId))
                            .and(w -> w
                                    .like(EvalDatasetItem::getQuestion, keyword)
                                    .or().like(EvalDatasetItem::getReferenceAnswer, keyword)))
                    .stream().map(EvalDatasetItem::getId).collect(Collectors.toSet());
            wrapper.and(w -> w
                    .like(EvalJudgeResult::getReason, keyword)
                    .or().like(EvalJudgeResult::getDimensions, keyword)
                    .or().in(!matchingItemIds.isEmpty(), EvalJudgeResult::getDatasetItemId, matchingItemIds)
                    .or().and(matchingItemIds.isEmpty(), q -> q.apply("1 = 0"))); // 无匹配时返回空
        }
        wrapper.orderByAsc(EvalJudgeResult::getId);

        // 关键词搜索时用较大但有界的 page size，避免 OOM
        int fetchSize = needKeywordFilter ? Math.min(50000, Math.max(page * size * 10, 1000)) : size;
        Page<EvalJudgeResult> pageObj = new Page<>(needKeywordFilter ? 1 : page, fetchSize);
        Page<EvalJudgeResult> result = judgeResultMapper.selectPage(pageObj, wrapper);

        List<EvalJudgeResult> records = result.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(List.of(), 0, page, size);
        }

        List<BadcaseVO> voList = enrichBadcaseVO(records, taskId);

        // 关键词搜索：DB层已过滤 reason/dimensions/question，这里再对 modelResponse 做二次过滤
        if (needKeywordFilter) {
            String kw = keyword.trim().toLowerCase();
            voList = voList.stream().filter(vo ->
                    (vo.getReason() != null && vo.getReason().toLowerCase().contains(kw))
                    || (vo.getDimensions() != null && vo.getDimensions().toLowerCase().contains(kw))
                    || (vo.getQuestion() != null && vo.getQuestion().toLowerCase().contains(kw))
                    || (vo.getModelResponse() != null && vo.getModelResponse().toLowerCase().contains(kw))
            ).collect(Collectors.toList());
            // 手动分页
            int from = Math.min((page - 1) * size, voList.size());
            int to = Math.min(from + size, voList.size());
            return new PageResult<>(new ArrayList<>(voList.subList(from, to)), voList.size(), page, size);
        }

        return new PageResult<>(voList, result.getTotal(), page, size);
    }

    @Override
    public List<BadcaseVO> listJudgeResults(Long taskId, Long promptId, Long modelConfigId) {
        List<EvalJudgeResult> records = judgeResultMapper.selectList(
                new LambdaQueryWrapper<EvalJudgeResult>()
                        .eq(EvalJudgeResult::getTaskId, taskId)
                        // 只取整体判定记录
                        .isNull(EvalJudgeResult::getDimension)
                        .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                        .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                        .orderByAsc(EvalJudgeResult::getId));

        if (records.isEmpty()) return List.of();
        return enrichBadcaseVO(records, taskId);
    }

    @Override
    public List<BadcaseVO> listDimensionResults(Long taskId, Long modelConfigId) {
        List<EvalJudgeResult> records = judgeResultMapper.selectList(
                new LambdaQueryWrapper<EvalJudgeResult>()
                        .eq(EvalJudgeResult::getTaskId, taskId)
                        .isNotNull(EvalJudgeResult::getDimension)
                        .eq(EvalJudgeResult::getJudgeStatus, "JUDGED")
                        .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                        .orderByAsc(EvalJudgeResult::getModelConfigId)
                        .orderByAsc(EvalJudgeResult::getDatasetItemId)
                        .orderByAsc(EvalJudgeResult::getDimension));
        if (records.isEmpty()) return List.of();
        return enrichBadcaseVO(records, taskId);
    }

    /**
     * 根据任务ID获取关联的数据集ID
     */
    private Long getDatasetId(Long taskId) {
        EvalTask task = taskMapper.selectById(taskId);
        return task != null ? task.getDatasetId() : null;
    }

    /**
     * 批量补充 EvalJudgeResult → BadcaseVO 的关联字段
     * （question / referenceAnswer / modelResponse / modelName / promptName）
     */
    private List<BadcaseVO> enrichBadcaseVO(List<EvalJudgeResult> records, Long taskId) {
        // 批量查 dataset_item 补充 question / referenceAnswer
        Set<Long> datasetItemIds = records.stream().map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet());
        Map<Long, EvalDatasetItem> itemMap = datasetItemMapper.selectBatchIds(datasetItemIds)
                .stream().collect(Collectors.toMap(EvalDatasetItem::getId, i -> i));

        // 批量查 eval_result 补充 modelResponse
        Set<Long> modelConfigIds = records.stream().map(EvalJudgeResult::getModelConfigId).collect(Collectors.toSet());
        List<EvalResult> evalResults = resultMapper.selectList(
                new LambdaQueryWrapper<EvalResult>()
                        .eq(EvalResult::getTaskId, taskId)
                        .in(EvalResult::getModelConfigId, modelConfigIds));
        Map<String, EvalResult> resultMap = evalResults.stream()
                .collect(Collectors.toMap(
                        r -> r.getTaskId() + "_" + r.getModelConfigId() + "_" + r.getDatasetItemId(),
                        r -> r, (a, b) -> a));

        // 批量查模型/Prompt 名称
        Map<Long, String> modelNameMap = modelConfigMapper.selectBatchIds(modelConfigIds)
                .stream().collect(Collectors.toMap(EvalModelConfig::getId, EvalModelConfig::getName));
        Set<Long> promptIds = records.stream().map(EvalJudgeResult::getPromptId).collect(Collectors.toSet());
        Map<Long, String> promptNameMap = promptMapper.selectBatchIds(promptIds)
                .stream().collect(Collectors.toMap(EvalPrompt::getId, EvalPrompt::getName));

        return records.stream().map(jr -> {
            BadcaseVO vo = new BadcaseVO();
            vo.setId(jr.getId());
            vo.setTaskId(jr.getTaskId());
            vo.setModelConfigId(jr.getModelConfigId());
            vo.setDatasetItemId(jr.getDatasetItemId());
            vo.setPromptId(jr.getPromptId());
            vo.setDimension(jr.getDimension());
            vo.setIsBadcase(jr.getIsBadcase());
            vo.setDimensions(jr.getDimensions());
            vo.setReason(jr.getReason());
            vo.setParsedResult(jr.getParsedResult());
            vo.setJudgeStatus(jr.getJudgeStatus());
            vo.setCachedTokens(jr.getCachedTokens());
            vo.setCreatedAt(jr.getCreatedAt());

            EvalDatasetItem item = itemMap.get(jr.getDatasetItemId());
            if (item != null) {
                vo.setQuestion(item.getQuestion());
                vo.setReferenceAnswer(item.getReferenceAnswer());
                vo.setContext(item.getContext());
                vo.setExtraFields(item.getExtraFields());
            }

            EvalResult er = resultMap.get(jr.getTaskId() + "_" + jr.getModelConfigId() + "_" + jr.getDatasetItemId());
            if (er != null) {
                vo.setModelResponse(er.getResponse());
            }

            vo.setModelName(modelNameMap.getOrDefault(jr.getModelConfigId(), "模型" + jr.getModelConfigId()));
            vo.setPromptName(promptNameMap.getOrDefault(jr.getPromptId(), "Prompt" + jr.getPromptId()));
            return vo;
        }).collect(Collectors.toList());
    }

    @Override
    public String generateReport(Long taskId) {
        EvalTask task = taskMapper.selectById(taskId);
        if (task == null) return "<html><body><h1>错误</h1><p>评测任务不存在</p></body></html>";

        // 加载关联数据
        List<EvalTaskModel> taskModels = taskModelMapper.selectList(
                new LambdaQueryWrapper<EvalTaskModel>().eq(EvalTaskModel::getTaskId, taskId));
        List<EvalTaskPrompt> taskPrompts = taskPromptMapper.selectList(
                new LambdaQueryWrapper<EvalTaskPrompt>().eq(EvalTaskPrompt::getTaskId, taskId));
        List<EvalTaskSummary> summaries = getSummary(taskId);

        // 汇总数据（三态：goodcase / badcase / unknown）
        int totalCount = 0, goodCount = 0, badcaseCount = 0, unknownCount = 0;
        for (EvalTaskSummary s : summaries) {
            if (s.getDimension() == null) {
                totalCount += s.getTotalCount() != null ? s.getTotalCount() : 0;
                goodCount += s.getGoodCount() != null ? s.getGoodCount() : 0;
                badcaseCount += s.getBadcaseCount() != null ? s.getBadcaseCount() : 0;
                unknownCount += s.getUnknownCount() != null ? s.getUnknownCount() : 0;
            }
        }
        double badcaseRate = totalCount > 0 ? (badcaseCount * 100.0 / totalCount) : 0;
        double goodRate = totalCount > 0 ? (goodCount * 100.0 / totalCount) : 0;
        double unknownRate = totalCount > 0 ? (unknownCount * 100.0 / totalCount) : 0;

        // 维度汇总
        List<EvalTaskSummary> dimSums = summaries.stream()
                .filter(s -> s.getDimension() != null)
                .sorted((a, b) -> Double.compare(b.getBadcaseRate().doubleValue(), a.getBadcaseRate().doubleValue()))
                .collect(Collectors.toList());

        // Badcase列表
        List<EvalJudgeResult> allBadcases = judgeResultMapper.selectList(
                new LambdaQueryWrapper<EvalJudgeResult>()
                        .eq(EvalJudgeResult::getTaskId, taskId)
                        .isNull(EvalJudgeResult::getDimension)
                        .eq(EvalJudgeResult::getIsBadcase, 1));

        // ===== 批量加载关联数据，避免 N+1 =====
        // 模型名
        Set<Long> modelIds = taskModels.stream().map(EvalTaskModel::getModelConfigId).collect(Collectors.toSet());
        Map<Long, String> modelNameMap = modelIds.isEmpty() ? Map.of()
                : modelConfigMapper.selectBatchIds(modelIds).stream()
                        .collect(Collectors.toMap(EvalModelConfig::getId, EvalModelConfig::getName, (a, b) -> a));
        // Prompt名
        Set<Long> promptIds = taskPrompts.stream().map(EvalTaskPrompt::getPromptId).collect(Collectors.toSet());
        Map<Long, String> promptNameMap = promptIds.isEmpty() ? Map.of()
                : promptMapper.selectBatchIds(promptIds).stream()
                        .collect(Collectors.toMap(EvalPrompt::getId, EvalPrompt::getName, (a, b) -> a));
        // Badcase 关联的数据集条目
        Map<Long, EvalDatasetItem> badcaseItemMap = allBadcases.isEmpty() ? Map.of()
                : datasetItemMapper.selectBatchIds(allBadcases.stream()
                        .map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(EvalDatasetItem::getId, i -> i, (a, b) -> a));

        // 模型名列表
        String modelTags = taskModels.stream()
                .map(tm -> modelNameMap.getOrDefault(tm.getModelConfigId(), ""))
                .filter(n -> !n.isEmpty())
                .map(n -> "<span class=\"tag\">" + esc(n) + "</span>")
                .collect(Collectors.joining(""));

        String promptStr = taskPrompts.stream()
                .map(tp -> promptNameMap.getOrDefault(tp.getPromptId(), ""))
                .filter(n -> !n.isEmpty())
                .collect(Collectors.joining("、"));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">");
        sb.append("<title>").append(esc(task.getName())).append(" · 评测报告</title>");
        sb.append("<style>");
        sb.append(":root{--bg:#f4f5f7;--card:#fff;--border:#e4e7ed;--text:#1a202c;--text-sec:#6b7280;--accent:#5b6cf8;--accent-l:#eef0fe;--green:#16a34a;--green-l:#dcfce7;--red:#dc2626;--red-l:#fee2e2;--orange:#ea580c}");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif;background:var(--bg);color:var(--text);line-height:1.6;font-size:14px}");
        sb.append(".page{max-width:1280px;margin:0 auto;padding:28px 20px}");
        sb.append(".report-header{background:linear-gradient(135deg,#4f46e5,#7c3aed);border-radius:16px;padding:32px 36px;margin-bottom:24px;color:#fff}");
        sb.append(".report-header h1{font-size:22px;font-weight:700;margin-bottom:4px}");
        sb.append(".report-header .meta{font-size:13px;opacity:.8;margin-top:6px}");
        sb.append(".tag{display:inline-block;background:rgba(255,255,255,.18);border-radius:20px;padding:2px 10px;font-size:12px;margin:4px 4px 0 0}");
        sb.append(".kpi-row{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:24px}");
        sb.append(".kpi{background:var(--card);border-radius:12px;padding:20px 22px;border:1px solid var(--border);position:relative;overflow:hidden}");
        sb.append(".kpi::before{content:'';position:absolute;top:0;left:0;right:0;height:3px}");
        sb.append(".kpi.t::before{background:var(--accent)}.kpi.g::before{background:var(--green)}.kpi.b::before{background:var(--red)}.kpi.r::before{background:var(--orange)}");
        sb.append(".kpi .num{font-size:30px;font-weight:800;line-height:1;margin-bottom:4px}");
        sb.append(".kpi.t .num{color:var(--accent)}.kpi.g .num{color:var(--green)}.kpi.b .num{color:var(--red)}.kpi.r .num{color:var(--orange)}");
        sb.append(".kpi .label{font-size:12px;color:var(--text-sec)}");
        sb.append(".section{background:var(--card);border-radius:12px;border:1px solid var(--border);margin-bottom:20px;overflow:hidden}");
        sb.append(".sec-hd{padding:18px 24px 0}");
        sb.append(".sec-hd h2{font-size:15px;font-weight:700;color:#111827}");
        sb.append(".sec-hd h2::before{content:'';display:inline-block;width:4px;height:16px;background:var(--accent);border-radius:2px;margin-right:8px;vertical-align:middle}");
        sb.append(".sec-bd{padding:16px 24px 22px}");
        sb.append(".topic-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(180px,1fr));gap:12px}");
        sb.append(".topic-card{border-radius:10px;border:1.5px solid var(--border);padding:14px 16px}");
        sb.append(".tc-name{font-size:13px;font-weight:600;margin-bottom:8px}");
        sb.append(".tc-rate{font-size:24px;font-weight:800;line-height:1}");
        sb.append(".tc-sub{font-size:11px;color:var(--text-sec);margin-top:2px}");
        sb.append(".best .tc-rate{color:var(--green)}.warn .tc-rate{color:var(--orange)}.bad-t .tc-rate{color:var(--red)}");
        sb.append(".mini-bar{height:6px;border-radius:3px;background:#f3f4f6;margin-top:10px;overflow:hidden}");
        sb.append(".mini-bar-fill{height:100%;border-radius:3px;background:linear-gradient(90deg,var(--green),#4ade80)}");
        sb.append(".insight{background:#fffbeb;border:1px solid #fde047;border-radius:10px;padding:14px 18px;margin-top:14px}");
        sb.append(".insight-title{font-size:13px;font-weight:700;color:#92400e;margin-bottom:6px}");
        sb.append(".insight ul{padding-left:16px}.insight li{font-size:13px;color:#78350f;margin-bottom:3px;line-height:1.6}");
        sb.append(".ind-table{width:100%;border-collapse:collapse;font-size:13px}");
        sb.append(".ind-table thead th{background:#f8f9fb;padding:9px 10px;font-weight:600;text-align:left;border-bottom:2px solid var(--border)}");
        sb.append(".ind-table thead th.c{text-align:center}");
        sb.append(".ind-table tbody td{padding:9px 10px;border-bottom:1px solid #f0f2f5;vertical-align:middle}");
        sb.append(".ind-table tbody tr:last-child td{border-bottom:none}");
        sb.append(".ind-table tbody tr:hover{background:#fafbfd}");
        sb.append(".badge{display:inline-block;padding:2px 8px;border-radius:20px;font-size:11px;font-weight:600}");
        sb.append(".badge-g{background:var(--green-l);color:#15803d}.badge-r{background:var(--red-l);color:#b91c1c}.badge-u{background:#ffedd5;color:#c2410c}");
        sb.append(".hbar{display:flex;height:20px;border-radius:5px;overflow:hidden;background:#f3f4f6;min-width:150px}");
        sb.append(".hbar-good{background:var(--green);display:flex;align-items:center;justify-content:flex-end;padding-right:5px;color:#fff;font-size:11px;font-weight:600}");
        sb.append(".hbar-bad{background:var(--red);display:flex;align-items:center;padding-left:4px;color:#fff;font-size:11px;font-weight:600}");
        sb.append(".hbar-unk{background:var(--orange);display:flex;align-items:center;justify-content:center;color:#fff;font-size:11px;font-weight:600}");
        sb.append(".bc-card{border:1px solid var(--border);border-radius:8px;margin-bottom:8px;overflow:hidden}");
        sb.append(".bc-head{background:#f8f9fb;padding:9px 14px;display:flex;align-items:flex-start;gap:10px}");
        sb.append(".bc-seq{font-size:11px;color:var(--text-sec);font-weight:500;white-space:nowrap;margin-top:1px}");
        sb.append(".bc-q{font-size:13px;font-weight:600;color:var(--text)}");
        sb.append(".bc-body{padding:9px 14px;font-size:12px;color:#374151;line-height:1.7;border-top:1px solid #f0f2f5}");
        sb.append(".bc-topic{font-size:11px;color:var(--text-sec);margin-bottom:3px}");
        sb.append(".bc-more{text-align:center;font-size:12px;color:var(--text-sec);padding:8px;background:#f9fafb;border-radius:6px;margin-top:4px}");
        sb.append("@media(max-width:900px){.kpi-row{grid-template-columns:repeat(2,1fr)}.topic-grid{grid-template-columns:repeat(2,1fr)}}");
        sb.append("</style></head><body><div class=\"page\">");

        // Header
        sb.append("<div class=\"report-header\">");
        sb.append("<h1>").append(esc(task.getName())).append(" · 评测报告</h1>");
        sb.append("<div class=\"meta\">评测日期：").append(task.getCreatedAt() != null ? task.getCreatedAt().format(fmt) : "-")
                .append(" · 评测Prompt：").append(esc(promptStr)).append("</div>");
        sb.append("<div>").append(modelTags).append("</div></div>");

        // KPI（三态）
        sb.append("<div class=\"kpi-row\">");
        sb.append(kpiCard("t", String.valueOf(totalCount), "总评测条数"));
        sb.append(kpiCard("g", String.valueOf(goodCount), "Goodcase（" + String.format("%.1f", goodRate) + "%）"));
        sb.append(kpiCard("b", String.valueOf(badcaseCount), "Badcase（" + String.format("%.1f", badcaseRate) + "%）"));
        sb.append(kpiCard("r", String.valueOf(unknownCount), "Unknown（" + String.format("%.1f", unknownRate) + "%）"));
        sb.append("</div>");

        // 维度概览
        if (!dimSums.isEmpty()) {
            sb.append("<div class=\"section\"><div class=\"sec-hd\"><h2>各维度 Badcase 率概览</h2></div><div class=\"sec-bd\">");
            sb.append("<div class=\"topic-grid\">");
            for (EvalTaskSummary ds : dimSums) {
                double adoptRate = 100 - ds.getBadcaseRate().doubleValue();
                String cls = ds.getBadcaseRate().doubleValue() > 30 ? "bad-t" : ds.getBadcaseRate().doubleValue() > 15 ? "warn" : "best";
                int good = ds.getGoodCount() != null ? ds.getGoodCount() : ds.getTotalCount() - ds.getBadcaseCount();
                int unknown = ds.getUnknownCount() != null ? ds.getUnknownCount() : 0;
                sb.append("<div class=\"topic-card ").append(cls).append("\">");
                sb.append("<div class=\"tc-name\">").append(esc(ds.getDimension())).append("</div>");
                sb.append("<div class=\"tc-rate\">").append(String.format("%.1f", adoptRate)).append("%</div>");
                sb.append("<div class=\"tc-sub\">采纳率 · ").append(good).append("/").append(ds.getTotalCount())
                        .append(" Goodcase").append(unknown > 0 ? " · Unknown " + unknown : "").append("</div>");
                sb.append("<div class=\"mini-bar\"><div class=\"mini-bar-fill\" style=\"width:").append(adoptRate).append("%\"></div></div>");
                sb.append("</div>");
            }
            sb.append("</div>");

            // 关键发现
            EvalTaskSummary worst = dimSums.get(0);
            EvalTaskSummary best = dimSums.get(dimSums.size() - 1);
            sb.append("<div class=\"insight\"><div class=\"insight-title\">📌 关键发现</div><ul>");
            sb.append("<li>最大问题维度为「").append(esc(worst.getDimension())).append("」，Badcase 率 ")
                    .append(String.format("%.1f", worst.getBadcaseRate().doubleValue())).append("%，共 ").append(worst.getBadcaseCount()).append(" 条</li>");
            sb.append("<li>最优维度为「").append(esc(best.getDimension())).append("」，Badcase 率 ")
                    .append(String.format("%.1f", best.getBadcaseRate().doubleValue())).append("%</li>");
            String conclusion = badcaseRate > 20 ? "需要重点关注" : badcaseRate > 10 ? "仍有改善空间" : "表现良好";
            sb.append("<li>整体 Badcase 率 ").append(String.format("%.1f", badcaseRate)).append("%，").append(conclusion).append("</li>");
            sb.append("</ul></div></div></div>");
        }

        // 指标总览表
        if (!dimSums.isEmpty()) {
            sb.append("<div class=\"section\"><div class=\"sec-hd\"><h2>各指标 Badcase 率总览</h2></div><div class=\"sec-bd\">");
            sb.append("<table class=\"ind-table\"><thead><tr>");
            sb.append("<th style=\"width:20%\">维度</th><th class=\"c\" style=\"width:9%\">Good</th><th class=\"c\" style=\"width:9%\">Bad</th><th class=\"c\" style=\"width:9%\">Unknown</th>");
            sb.append("<th class=\"c\" style=\"width:9%\">采纳率</th><th style=\"width:22%\">占比</th><th style=\"width:22%\">分布</th>");
            sb.append("</tr></thead><tbody>");

            // 整体行
            sb.append("<tr><td><strong>整体</strong></td>");
            sb.append("<td class=\"c\"><span class=\"badge badge-g\">").append(goodCount).append("</span></td>");
            sb.append("<td class=\"c\"><span class=\"badge badge-r\">").append(badcaseCount).append("</span></td>");
            sb.append("<td class=\"c\"><span class=\"badge badge-u\">").append(unknownCount).append("</span></td>");
            sb.append("<td class=\"c\"><strong style=\"color:#16a34a\">").append(String.format("%.1f", goodRate)).append("%</strong></td>");
            sb.append("<td>").append(hbar(goodRate, badcaseRate, unknownRate, goodCount, badcaseCount, unknownCount)).append("</td><td></td></tr>");

            // 维度行
            for (EvalTaskSummary ds : dimSums) {
                double adoptRate = 100 - ds.getBadcaseRate().doubleValue();
                int good = ds.getGoodCount() != null ? ds.getGoodCount() : ds.getTotalCount() - ds.getBadcaseCount();
                int unknown = ds.getUnknownCount() != null ? ds.getUnknownCount() : 0;
                int dimTotal = ds.getTotalCount() != null ? ds.getTotalCount() : 0;
                double goodPct = dimTotal > 0 ? good * 100.0 / dimTotal : 0;
                double badPct = ds.getBadcaseRate() != null ? ds.getBadcaseRate().doubleValue() : 0;
                double unkPct = dimTotal > 0 ? unknown * 100.0 / dimTotal : 0;
                String rateColor = ds.getBadcaseRate().doubleValue() > 30 ? "#dc2626" : ds.getBadcaseRate().doubleValue() > 15 ? "#ea580c" : "#16a34a";
                sb.append("<tr><td>").append(esc(ds.getDimension())).append("</td>");
                sb.append("<td class=\"c\"><span class=\"badge badge-g\">").append(good).append("</span></td>");
                sb.append("<td class=\"c\"><span class=\"badge badge-r\">").append(ds.getBadcaseCount()).append("</span></td>");
                sb.append("<td class=\"c\"><span class=\"badge badge-u\">").append(unknown).append("</span></td>");
                sb.append("<td class=\"c\"><strong style=\"color:").append(rateColor).append("\">").append(String.format("%.1f", adoptRate)).append("%</strong></td>");
                sb.append("<td>").append(hbar(goodPct, badPct, unkPct, good, ds.getBadcaseCount(), unknown)).append("</td>");
                // 模型分布（用已批量加载的 modelNameMap，避免 N+1）
                String dimDist = summaries.stream()
                        .filter(s -> s.getDimension() != null && s.getDimension().equals(ds.getDimension()))
                        .map(s -> "<span class=\"badge\" style=\"background:#d9770620;color:#d97706;font-size:11px;margin:1px 2px\">" + esc(modelNameMap.getOrDefault(s.getModelConfigId(), String.valueOf(s.getModelConfigId()))) + ":" + s.getBadcaseCount() + "</span>")
                        .collect(Collectors.joining(" "));
                sb.append("<td>").append(dimDist).append("</td></tr>");
            }
            sb.append("</tbody></table></div></div>");
        }

        // Badcase详情
        if (!allBadcases.isEmpty()) {
            sb.append("<div class=\"section\"><div class=\"sec-hd\"><h2>Badcase 详情（共 ").append(allBadcases.size()).append(" 条，展示前 50 条）</h2></div><div class=\"sec-bd\">");
            List<EvalJudgeResult> shown = allBadcases.stream().limit(50).collect(Collectors.toList());
            for (EvalJudgeResult bc : shown) {
                EvalDatasetItem item = badcaseItemMap.get(bc.getDatasetItemId());
                sb.append("<div class=\"bc-card\">");
                sb.append("<div class=\"bc-head\"><span class=\"bc-seq\">#").append(bc.getDatasetItemId()).append("</span>");
                sb.append("<span class=\"bc-q\">").append(item != null ? esc(item.getQuestion()) : "").append("</span></div>");
                sb.append("<div class=\"bc-body\">");
                if (item != null && item.getReferenceAnswer() != null) {
                    sb.append("<div class=\"bc-topic\">📌 ").append(esc(item.getReferenceAnswer())).append("</div>");
                }
                sb.append("<div class=\"bc-reason\">").append(bc.getReason() != null ? esc(bc.getReason()) : "-").append("</div>");
                sb.append("</div></div>");
            }
            if (allBadcases.size() > 50) {
                sb.append("<div class=\"bc-more\">… 共 ").append(allBadcases.size()).append(" 条，已展示前 50 条</div>");
            }
            sb.append("</div></div>");
        }

        sb.append("</div></body></html>");
        return sb.toString();
    }

    private String kpiCard(String cls, String num, String label) {
        return "<div class=\"kpi " + cls + "\"><div class=\"num\">" + num + "</div><div class=\"label\">" + label + "</div></div>";
    }

    private String hbar(double goodPct, double badPct, double unknownPct, int goodCount, int badCount, int unknownCount) {
        String goodSeg = goodCount > 0
                ? "<div class=\"hbar-good\" style=\"width:" + goodPct + "%\">" + Math.round(goodPct) + "%</div>" : "";
        String badSeg = badCount > 0
                ? "<div class=\"hbar-bad\" style=\"width:" + badPct + "%\">" + Math.round(badPct) + "%</div>" : "";
        String unkSeg = unknownCount > 0
                ? "<div class=\"hbar-unk\" style=\"width:" + unknownPct + "%\">" + Math.round(unknownPct) + "%</div>" : "";
        return "<div class=\"hbar\">" + goodSeg + badSeg + unkSeg + "</div>";
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String getModelName(Long id) {
        if (id == null) return "-";
        EvalModelConfig m = modelConfigMapper.selectById(id);
        return m != null ? m.getName() : String.valueOf(id);
    }

    private String getPromptName(Long id) {
        if (id == null) return "-";
        EvalPrompt p = promptMapper.selectById(id);
        return p != null ? p.getName() : String.valueOf(id);
    }

    // ==================== 人工校验（人机 + 人人） ====================

    @Override
    public void submitHumanReview(HumanReviewDTO dto) {
        // 校验参数
        if (dto.getIsBadcaseHuman() != null && dto.getIsBadcaseHuman() != 0 && dto.getIsBadcaseHuman() != 1) {
            throw new BusinessException("人工判定只能为 0(非badcase) 或 1(badcase)");
        }
        if (StrUtil.isBlank(dto.getReviewer())) {
            throw new BusinessException("校验人不能为空");
        }
        // 角色归一化：默认普通评委
        String role = "expert".equalsIgnoreCase(dto.getRole()) ? "expert" : "normal";

        // 查询该样本的 AI 判定（整体记录 dimension IS NULL），用于人机对比
        EvalJudgeResult aiResult = judgeResultMapper.selectOne(
                new LambdaQueryWrapper<EvalJudgeResult>()
                        .eq(EvalJudgeResult::getTaskId, dto.getTaskId())
                        .eq(EvalJudgeResult::getModelConfigId, dto.getModelConfigId())
                        .eq(EvalJudgeResult::getPromptId, dto.getPromptId())
                        .eq(EvalJudgeResult::getDatasetItemId, dto.getDatasetItemId())
                        .isNull(EvalJudgeResult::getDimension)
                        .last("LIMIT 1"));
        if (aiResult == null) {
            throw new BusinessException("未找到对应的 AI 判定结果");
        }

        // 校验人独立判定表：同一人同一样本一条（upsert）
        EvalReviewerVerdict verdict = reviewerVerdictMapper.selectOne(
                new LambdaQueryWrapper<EvalReviewerVerdict>()
                        .eq(EvalReviewerVerdict::getTaskId, dto.getTaskId())
                        .eq(EvalReviewerVerdict::getModelConfigId, dto.getModelConfigId())
                        .eq(EvalReviewerVerdict::getPromptId, dto.getPromptId())
                        .eq(EvalReviewerVerdict::getDatasetItemId, dto.getDatasetItemId())
                        .eq(EvalReviewerVerdict::getReviewer, dto.getReviewer())
                        .last("LIMIT 1"));

        if (verdict != null) {
            verdict.setIsBadcaseHuman(dto.getIsBadcaseHuman());
            verdict.setComment(dto.getComment());
            verdict.setRole(role);
            reviewerVerdictMapper.updateById(verdict);
        } else {
            verdict = new EvalReviewerVerdict();
            verdict.setTaskId(dto.getTaskId());
            verdict.setModelConfigId(dto.getModelConfigId());
            verdict.setPromptId(dto.getPromptId());
            verdict.setDatasetItemId(dto.getDatasetItemId());
            verdict.setReviewer(dto.getReviewer());
            verdict.setRole(role);
            verdict.setIsBadcaseHuman(dto.getIsBadcaseHuman());
            verdict.setComment(dto.getComment());
            verdict.setCreatedAt(java.time.LocalDateTime.now());
            reviewerVerdictMapper.insert(verdict);
        }
        log.info("人工校验提交: taskId={}, modelId={}, itemId={}, reviewer={}, role={}, human={}",
                dto.getTaskId(), dto.getModelConfigId(), dto.getDatasetItemId(), dto.getReviewer(), role, dto.getIsBadcaseHuman());
    }

    @Override
    public HumanReviewStatsVO getHumanReviewStats(Long taskId, Long modelConfigId, Long promptId) {
        HumanReviewStatsVO vo = new HumanReviewStatsVO();

        // ===== 1. 所有 AI 判定样本（整体记录） =====
        LambdaQueryWrapper<EvalJudgeResult> judgeWrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getJudgeStatus, com.eval.common.constant.JudgeStatus.JUDGED);
        List<EvalJudgeResult> allJudges = judgeResultMapper.selectList(judgeWrapper);
        vo.setTotalCount(allJudges.size());
        vo.setAiBadcaseCount((int) allJudges.stream().filter(j -> j.getIsBadcase() != null && j.getIsBadcase() == 1).count());

        // ===== 2. 所有人的判定记录 =====
        LambdaQueryWrapper<EvalReviewerVerdict> verdictWrapper = new LambdaQueryWrapper<EvalReviewerVerdict>()
                .eq(EvalReviewerVerdict::getTaskId, taskId)
                .eq(modelConfigId != null, EvalReviewerVerdict::getModelConfigId, modelConfigId)
                .eq(promptId != null, EvalReviewerVerdict::getPromptId, promptId);
        List<EvalReviewerVerdict> verdicts = reviewerVerdictMapper.selectList(verdictWrapper);

        vo.setReviewedCount(verdicts.size());
        vo.setReviewerCount((int) verdicts.stream().map(EvalReviewerVerdict::getReviewer).distinct().count());
        vo.setReviewedSampleCount((int) verdicts.stream()
                .map(v -> v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()).distinct().count());

        // ===== 3. 人机一致率：AI 判定 vs 每个校验人的判定 =====
        // 构建 AI 判定索引: (model+prompt+item) -> is_badcase
        Map<String, Integer> aiJudgeMap = allJudges.stream()
                .collect(Collectors.toMap(
                        j -> j.getModelConfigId() + "_" + j.getPromptId() + "_" + j.getDatasetItemId(),
                        j -> j.getIsBadcase() != null ? j.getIsBadcase() : 0,
                        (a, b) -> a));
        int agreeWithAi = 0;
        for (EvalReviewerVerdict v : verdicts) {
            Integer ai = aiJudgeMap.get(v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId());
            if (ai != null && ai.equals(v.getIsBadcaseHuman())) {
                agreeWithAi++;
            }
        }
        vo.setAgreeWithAiCount(agreeWithAi);
        vo.setHumanAiAgreementRate(verdicts.isEmpty() ? 0 : agreeWithAi * 100.0 / verdicts.size());

        // ===== 4. 人人一致率：≥2人校验的样本中，所有校验人判定一致的比例 =====
        Map<String, List<EvalReviewerVerdict>> bySample = verdicts.stream()
                .collect(Collectors.groupingBy(v -> v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()));
        int multiReviewerSamples = 0;
        int allAgreeSamples = 0;
        for (Map.Entry<String, List<EvalReviewerVerdict>> e : bySample.entrySet()) {
            List<EvalReviewerVerdict> list = e.getValue();
            if (list.size() >= 2) {
                multiReviewerSamples++;
                int first = list.get(0).getIsBadcaseHuman() != null ? list.get(0).getIsBadcaseHuman() : -1;
                boolean allSame = first >= 0 && list.stream().allMatch(v ->
                        v.getIsBadcaseHuman() != null && v.getIsBadcaseHuman() == first);
                if (allSame) allAgreeSamples++;
            }
        }
        vo.setMultiReviewerSampleCount(multiReviewerSamples);
        vo.setAllAgreeSampleCount(allAgreeSamples);
        vo.setHumanHumanAgreementRate(multiReviewerSamples > 0 ? allAgreeSamples * 100.0 / multiReviewerSamples : 0);

        // ===== 5. Kappa 系数（扣除偶然一致） =====
        computeKappa(vo, bySample);

        return vo;
    }

    /**
     * 计算 Fleiss' Kappa（多标注者整体）和 Cohen's Kappa 平均值（两两之间）
     * @param bySample 按样本分组的判定（key = model_prompt_item）
     */
    private void computeKappa(HumanReviewStatsVO vo, Map<String, List<EvalReviewerVerdict>> bySample) {
        // 只统计 ≥2 人校验的样本
        List<List<EvalReviewerVerdict>> multiSamples = bySample.values().stream()
                .filter(list -> list.size() >= 2)
                .collect(Collectors.toList());

        if (multiSamples.isEmpty()) {
            vo.setFleissKappa(Double.NaN);
            vo.setCohenKappaAvg(Double.NaN);
            vo.setCohenKappaPairCount(0);
            vo.setKappaLevel("样本不足");
            return;
        }

        // ===== Fleiss' Kappa =====
        // 构建矩阵 [样本数][2]：matrix[i][0]=good人数, matrix[i][1]=bad人数
        int[][] matrix = new int[multiSamples.size()][2];
        for (int i = 0; i < multiSamples.size(); i++) {
            for (EvalReviewerVerdict v : multiSamples.get(i)) {
                int verdict = v.getIsBadcaseHuman() != null ? v.getIsBadcaseHuman() : 0;
                matrix[i][verdict]++;
            }
        }
        double fleiss = com.eval.common.util.KappaCalculator.fleissKappa(matrix);

        // ===== Cohen's Kappa 平均值（两两校验人之间） =====
        // 收集所有校验人
        Set<String> reviewerNames = new LinkedHashSet<>();
        for (List<EvalReviewerVerdict> list : multiSamples) {
            for (EvalReviewerVerdict v : list) reviewerNames.add(v.getReviewer());
        }
        List<String> reviewers = new ArrayList<>(reviewerNames);

        // 为每对校验人构建"共同判定的样本"列表，算 Cohen's Kappa
        double cohenSum = 0;
        int pairCount = 0;
        for (int i = 0; i < reviewers.size(); i++) {
            for (int j = i + 1; j < reviewers.size(); j++) {
                String ra = reviewers.get(i);
                String rb = reviewers.get(j);
                List<Integer> va = new ArrayList<>();
                List<Integer> vb = new ArrayList<>();
                // 只取两人都判过的样本
                for (List<EvalReviewerVerdict> list : multiSamples) {
                    EvalReviewerVerdict va_ = list.stream().filter(v -> ra.equals(v.getReviewer())).findFirst().orElse(null);
                    EvalReviewerVerdict vb_ = list.stream().filter(v -> rb.equals(v.getReviewer())).findFirst().orElse(null);
                    if (va_ != null && vb_ != null
                            && va_.getIsBadcaseHuman() != null && vb_.getIsBadcaseHuman() != null) {
                        va.add(va_.getIsBadcaseHuman());
                        vb.add(vb_.getIsBadcaseHuman());
                    }
                }
                if (va.size() >= 2) {
                    double k = com.eval.common.util.KappaCalculator.cohenKappa(va, vb);
                    if (!Double.isNaN(k)) {
                        cohenSum += k;
                        pairCount++;
                    }
                }
            }
        }
        double cohenAvg = pairCount > 0 ? cohenSum / pairCount : Double.NaN;

        vo.setFleissKappa(fleiss);
        vo.setCohenKappaAvg(cohenAvg);
        vo.setCohenKappaPairCount(pairCount);
        // 档位以 Fleiss 为主（多人场景更标准），无值时退用 Cohen
        double refKappa = !Double.isNaN(fleiss) ? fleiss : cohenAvg;
        vo.setKappaLevel(com.eval.common.util.KappaCalculator.kappaLevel(refKappa));
    }

    @Override
    public List<BadcaseVO> listReviewSamples(Long taskId, Long promptId, Long modelConfigId,
                                              String reviewer, String role, int page, int size) {
        // 查该任务的整体判定记录（分页）
        int offset = (page - 1) * size;
        LambdaQueryWrapper<EvalJudgeResult> wrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getJudgeStatus, com.eval.common.constant.JudgeStatus.JUDGED)
                .orderByAsc(EvalJudgeResult::getId)
                .last("LIMIT " + size + " OFFSET " + offset);
        List<EvalJudgeResult> records = judgeResultMapper.selectList(wrapper);
        if (records.isEmpty()) return List.of();

        // 补充关联字段
        List<BadcaseVO> vos = enrichBadcaseVO(records, taskId);

        // 为每个样本补充校验人判定列表（按权限过滤：普通评委只看自己，专家看全部）
        boolean isExpert = "expert".equalsIgnoreCase(role);
        Set<String> sampleKeys = records.stream()
                .map(r -> r.getModelConfigId() + "_" + r.getPromptId() + "_" + r.getDatasetItemId())
                .collect(Collectors.toSet());
        if (!sampleKeys.isEmpty()) {
            // 查这些样本的所有判定
            List<EvalReviewerVerdict> allVerdicts = reviewerVerdictMapper.selectList(
                    new LambdaQueryWrapper<EvalReviewerVerdict>().eq(EvalReviewerVerdict::getTaskId, taskId));
            // 普通评委：只返回自己的判定；专家：返回全部
            if (!isExpert && StrUtil.isNotBlank(reviewer)) {
                allVerdicts = allVerdicts.stream()
                        .filter(v -> reviewer.equals(v.getReviewer()))
                        .collect(Collectors.toList());
            }
            Map<String, List<EvalReviewerVerdict>> verdictMap = allVerdicts.stream()
                    .filter(v -> sampleKeys.contains(v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()))
                    .collect(Collectors.groupingBy(v -> v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()));
            // 查金标准（数据集级别，按 model+item 查）
            Set<Long> goldItemIds = records.stream().map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet());
            Set<Long> goldModelIds = records.stream().map(EvalJudgeResult::getModelConfigId).collect(Collectors.toSet());
            List<EvalGoldLabel> allGolds = goldItemIds.isEmpty() ? List.of()
                    : goldLabelMapper.selectList(new LambdaQueryWrapper<EvalGoldLabel>()
                            .in(EvalGoldLabel::getModelConfigId, goldModelIds)
                            .in(EvalGoldLabel::getDatasetItemId, goldItemIds));
            Map<String, EvalGoldLabel> goldLabelMap = allGolds.stream()
                    .collect(Collectors.toMap(g -> g.getModelConfigId() + "_" + g.getDatasetItemId(),
                            g -> g, (a, b) -> a));

            for (BadcaseVO vo : vos) {
                String key = vo.getModelConfigId() + "_" + vo.getPromptId() + "_" + vo.getDatasetItemId();
                vo.setReviewerVerdicts(verdictMap.getOrDefault(key, List.of()));
                // 补充金标准（按 model+item 查，跨任务复用）
                String goldKey = vo.getModelConfigId() + "_" + vo.getDatasetItemId();
                EvalGoldLabel gold = goldLabelMap.get(goldKey);
                if (gold != null) vo.setGoldLabel(gold);
            }
        }
        return vos;
    }

    // ==================== 链路一：专家裁决 ====================

    @Override
    public List<BadcaseVO> listAdjudicationSamples(Long taskId, Long promptId, Long modelConfigId, int page, int size) {
        // 查该任务整体判定记录（分页）
        int offset = (page - 1) * size;
        LambdaQueryWrapper<EvalJudgeResult> wrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getJudgeStatus, com.eval.common.constant.JudgeStatus.JUDGED)
                .orderByAsc(EvalJudgeResult::getId)
                .last("LIMIT " + size + " OFFSET " + offset);
        List<EvalJudgeResult> records = judgeResultMapper.selectList(wrapper);
        if (records.isEmpty()) return List.of();

        List<BadcaseVO> vos = enrichBadcaseVO(records, taskId);

        // 查所有校验人判定 + 金标准
        Set<String> sampleKeys = records.stream()
                .map(r -> r.getModelConfigId() + "_" + r.getPromptId() + "_" + r.getDatasetItemId())
                .collect(Collectors.toSet());
        List<EvalReviewerVerdict> allVerdicts = reviewerVerdictMapper.selectList(
                new LambdaQueryWrapper<EvalReviewerVerdict>().eq(EvalReviewerVerdict::getTaskId, taskId));
        Map<String, List<EvalReviewerVerdict>> verdictMap = allVerdicts.stream()
                .filter(v -> sampleKeys.contains(v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()))
                .collect(Collectors.groupingBy(v -> v.getModelConfigId() + "_" + v.getPromptId() + "_" + v.getDatasetItemId()));
        // 金标准（model+item，跨任务复用）
        Set<Long> goldModelIds = records.stream().map(EvalJudgeResult::getModelConfigId).collect(Collectors.toSet());
        Set<Long> goldItemIds = records.stream().map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet());
        List<EvalGoldLabel> allGolds = goldModelIds.isEmpty() ? List.of()
                : goldLabelMapper.selectList(new LambdaQueryWrapper<EvalGoldLabel>()
                        .in(EvalGoldLabel::getModelConfigId, goldModelIds)
                        .in(EvalGoldLabel::getDatasetItemId, goldItemIds));
        Map<String, EvalGoldLabel> goldMap = allGolds.stream()
                .collect(Collectors.toMap(g -> g.getModelConfigId() + "_" + g.getDatasetItemId(),
                        g -> g, (a, b) -> a));

        // 只保留"有评委分歧且未裁决"或"已裁决"的样本（给专家看）
        List<BadcaseVO> result = new ArrayList<>();
        for (BadcaseVO vo : vos) {
            String key = vo.getModelConfigId() + "_" + vo.getPromptId() + "_" + vo.getDatasetItemId();
            List<EvalReviewerVerdict> vs = verdictMap.getOrDefault(key, List.of());
            vo.setReviewerVerdicts(vs);
            EvalGoldLabel gold = goldMap.get(vo.getModelConfigId() + "_" + vo.getDatasetItemId());
            vo.setGoldLabel(gold);
            // 计算是否有分歧
            boolean hasDisagreement = hasDisagreement(vs);
            vo.setHasDisagreement(hasDisagreement);
            // 专家视图：有分歧且未裁决 → 待裁决；已裁决的也展示
            if ((hasDisagreement && (gold == null || "PENDING".equals(gold.getStatus()))) || gold != null) {
                result.add(vo);
            }
        }
        return result;
    }

    /** 判断一组校验人判定是否有分歧 */
    private boolean hasDisagreement(List<EvalReviewerVerdict> vs) {
        if (vs == null || vs.size() < 2) return false;
        int first = vs.get(0).getIsBadcaseHuman() != null ? vs.get(0).getIsBadcaseHuman() : -1;
        if (first < 0) return false;
        return vs.stream().anyMatch(v -> v.getIsBadcaseHuman() == null || v.getIsBadcaseHuman() != first);
    }

    @Override
    public void adjudicate(AdjudicationDTO dto) {
        if (dto.getIsBadcase() == null || (dto.getIsBadcase() != 0 && dto.getIsBadcase() != 1)) {
            throw new BusinessException("裁决判定只能为 0 或 1");
        }
        if (StrUtil.isBlank(dto.getAdjudicator())) {
            throw new BusinessException("裁决专家不能为空");
        }

        // 查已有金标准（唯一键 = model + dataset_item，跨任务复用）
        EvalGoldLabel existing = goldLabelMapper.selectOne(
                new LambdaQueryWrapper<EvalGoldLabel>()
                        .eq(EvalGoldLabel::getModelConfigId, dto.getModelConfigId())
                        .eq(EvalGoldLabel::getDatasetItemId, dto.getDatasetItemId())
                        .last("LIMIT 1"));

        // 查该样本的校验人判定，算分歧
        List<EvalReviewerVerdict> vs = reviewerVerdictMapper.selectList(
                new LambdaQueryWrapper<EvalReviewerVerdict>()
                        .eq(EvalReviewerVerdict::getTaskId, dto.getTaskId())
                        .eq(EvalReviewerVerdict::getModelConfigId, dto.getModelConfigId())
                        .eq(EvalReviewerVerdict::getPromptId, dto.getPromptId())
                        .eq(EvalReviewerVerdict::getDatasetItemId, dto.getDatasetItemId()));
        boolean disagreement = hasDisagreement(vs);

        if (existing != null) {
            existing.setIsBadcase(dto.getIsBadcase());
            existing.setHasDisagreement(disagreement ? 1 : 0);
            existing.setAdjudicator(dto.getAdjudicator());
            existing.setAdjudicateComment(dto.getComment());
            existing.setStatus("CONFIRMED");
            goldLabelMapper.updateById(existing);
        } else {
            EvalGoldLabel gold = new EvalGoldLabel();
            gold.setTaskId(dto.getTaskId());
            gold.setModelConfigId(dto.getModelConfigId());
            gold.setPromptId(dto.getPromptId());
            gold.setDatasetItemId(dto.getDatasetItemId());
            gold.setIsBadcase(dto.getIsBadcase());
            gold.setHasDisagreement(disagreement ? 1 : 0);
            gold.setAdjudicator(dto.getAdjudicator());
            gold.setAdjudicateComment(dto.getComment());
            gold.setStatus("CONFIRMED");
            gold.setCreatedAt(java.time.LocalDateTime.now());
            goldLabelMapper.insert(gold);
        }
        log.info("专家裁决: taskId={}, itemId={}, adjudicator={}, gold={}, disagreement={}",
                dto.getTaskId(), dto.getDatasetItemId(), dto.getAdjudicator(), dto.getIsBadcase(), disagreement);
    }

    // ==================== 链路二：人机对比（AI vs 金标准） ====================

    @Override
    public HumanVsAiStatsVO getHumanVsAiStats(Long taskId, Long modelConfigId, Long promptId) {
        HumanVsAiStatsVO vo = new HumanVsAiStatsVO();

        // AI 判定（针对该 task）
        LambdaQueryWrapper<EvalJudgeResult> judgeWrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getJudgeStatus, com.eval.common.constant.JudgeStatus.JUDGED);
        List<EvalJudgeResult> judges = judgeResultMapper.selectList(judgeWrapper);
        if (judges.isEmpty()) {
            vo.setGoldCount(0); vo.setAgreeCount(0); vo.setDisagreeCount(0);
            vo.setAiFalsePositive(0); vo.setAiFalseNegative(0);
            vo.setDisagreementRate(0); vo.setAgreementRate(0);
            return vo;
        }

        // 金标准（按 model+item 查，跨任务复用）
        Set<Long> goldModelIds = judges.stream().map(EvalJudgeResult::getModelConfigId).collect(Collectors.toSet());
        Set<Long> goldItemIds = judges.stream().map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet());
        List<EvalGoldLabel> golds = goldLabelMapper.selectList(new LambdaQueryWrapper<EvalGoldLabel>()
                .in(EvalGoldLabel::getModelConfigId, goldModelIds)
                .in(EvalGoldLabel::getDatasetItemId, goldItemIds)
                .eq(EvalGoldLabel::getStatus, "CONFIRMED"));

        // AI 判定索引：model+item -> is_badcase
        Map<String, Integer> aiMap = judges.stream()
                .collect(Collectors.toMap(
                        j -> j.getModelConfigId() + "_" + j.getDatasetItemId(),
                        j -> j.getIsBadcase() != null ? j.getIsBadcase() : 0,
                        (a, b) -> a));

        int agree = 0, disagree = 0, fp = 0, fn = 0;
        for (EvalGoldLabel g : golds) {
            Integer ai = aiMap.get(g.getModelConfigId() + "_" + g.getDatasetItemId());
            if (ai == null) continue;
            int gold = g.getIsBadcase();
            if (ai == gold) {
                agree++;
            } else {
                disagree++;
                if (ai == 1 && gold == 0) fp++;       // AI 过严
                else if (ai == 0 && gold == 1) fn++;  // AI 漏判
            }
        }
        int total = agree + disagree;
        vo.setGoldCount(golds.size());
        vo.setAgreeCount(agree);
        vo.setDisagreeCount(disagree);
        vo.setAiFalsePositive(fp);
        vo.setAiFalseNegative(fn);
        vo.setDisagreementRate(total > 0 ? disagree * 100.0 / total : 0);
        vo.setAgreementRate(total > 0 ? agree * 100.0 / total : 0);
        return vo;
    }

    @Override
    public List<BadcaseVO> listHumanVsAiSamples(Long taskId, Long promptId, Long modelConfigId, int page, int size) {
        // 从该 task 的 AI 判定出发（整体记录）
        LambdaQueryWrapper<EvalJudgeResult> judgeWrapper = new LambdaQueryWrapper<EvalJudgeResult>()
                .eq(EvalJudgeResult::getTaskId, taskId)
                .eq(promptId != null, EvalJudgeResult::getPromptId, promptId)
                .eq(modelConfigId != null, EvalJudgeResult::getModelConfigId, modelConfigId)
                .isNull(EvalJudgeResult::getDimension)
                .eq(EvalJudgeResult::getJudgeStatus, com.eval.common.constant.JudgeStatus.JUDGED)
                .orderByAsc(EvalJudgeResult::getId)
                .last("LIMIT " + page * size);
        List<EvalJudgeResult> judges = judgeResultMapper.selectList(judgeWrapper);
        if (judges.isEmpty()) return List.of();

        // 查对应的金标准（model+item，跨任务复用）
        Set<Long> modelIds = judges.stream().map(EvalJudgeResult::getModelConfigId).collect(Collectors.toSet());
        Set<Long> itemIds = judges.stream().map(EvalJudgeResult::getDatasetItemId).collect(Collectors.toSet());
        List<EvalGoldLabel> golds = goldLabelMapper.selectList(new LambdaQueryWrapper<EvalGoldLabel>()
                .in(EvalGoldLabel::getModelConfigId, modelIds)
                .in(EvalGoldLabel::getDatasetItemId, itemIds)
                .eq(EvalGoldLabel::getStatus, "CONFIRMED"));
        Map<String, EvalGoldLabel> goldMap = golds.stream()
                .collect(Collectors.toMap(g -> g.getModelConfigId() + "_" + g.getDatasetItemId(),
                        g -> g, (a, b) -> a));

        // 数据集条目 + 模型名
        Map<Long, EvalDatasetItem> itemMap = datasetItemMapper.selectBatchIds(itemIds).stream()
                .collect(Collectors.toMap(EvalDatasetItem::getId, i -> i, (a, b) -> a));
        Map<Long, String> modelNameMap = modelConfigMapper.selectBatchIds(modelIds).stream()
                .collect(Collectors.toMap(EvalModelConfig::getId, EvalModelConfig::getName, (a, b) -> a));

        List<BadcaseVO> result = new ArrayList<>();
        for (EvalJudgeResult j : judges) {
            String goldKey = j.getModelConfigId() + "_" + j.getDatasetItemId();
            EvalGoldLabel g = goldMap.get(goldKey);
            if (g == null) continue;  // 没有金标准的样本不展示
            BadcaseVO vo = new BadcaseVO();
            vo.setId(j.getId());
            vo.setTaskId(taskId);
            vo.setModelConfigId(j.getModelConfigId());
            vo.setDatasetItemId(j.getDatasetItemId());
            vo.setPromptId(j.getPromptId());
            vo.setIsBadcase(j.getIsBadcase());
            vo.setReason(j.getReason());
            vo.setGoldLabel(g);
            vo.setHasDisagreement(g.getHasDisagreement() != null && g.getHasDisagreement() == 1);
            // AI vs 金标准 是否一致
            int ai = j.getIsBadcase() != null ? j.getIsBadcase() : 0;
            vo.setAiGoldAgree(ai == g.getIsBadcase());
            EvalDatasetItem item = itemMap.get(j.getDatasetItemId());
            if (item != null) {
                vo.setQuestion(item.getQuestion());
                vo.setReferenceAnswer(item.getReferenceAnswer());
                vo.setContext(item.getContext());
                vo.setExtraFields(item.getExtraFields());
            }
            vo.setModelName(modelNameMap.getOrDefault(j.getModelConfigId(), "模型" + j.getModelConfigId()));
            // 模型回答
            EvalResult er = resultMapper.selectOne(
                    new LambdaQueryWrapper<EvalResult>()
                            .eq(EvalResult::getTaskId, taskId)
                            .eq(EvalResult::getModelConfigId, j.getModelConfigId())
                            .eq(EvalResult::getDatasetItemId, j.getDatasetItemId())
                            .last("LIMIT 1"));
            if (er != null) vo.setModelResponse(er.getResponse());
            result.add(vo);
        }
        return result;
    }
}
