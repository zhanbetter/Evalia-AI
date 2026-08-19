package com.eval.worker;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eval.common.constant.JudgeStatus;
import com.eval.common.constant.KafkaTopic;
import com.eval.common.constant.TaskStatus;
import com.eval.dao.mapper.*;
import com.eval.model.entity.*;
import com.eval.service.LlmApiClient;
import com.eval.service.PromptGenerator;
import com.eval.service.harness.DatasetAdapter;
import com.eval.service.harness.EvalSample;
import com.eval.service.harness.LlmJudgeMetric;
import com.eval.service.harness.MetricResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AI评测 Judge Worker
 * 用评测Prompt调AI → AI返回 is_badcase + dimensions + reason → 解析入库 → 统计badcase率
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JudgeWorker {

    private final EvalTaskMapper taskMapper;
    private final EvalResultMapper resultMapper;
    private final EvalModelConfigMapper modelConfigMapper;
    private final EvalDatasetItemMapper datasetItemMapper;
    private final EvalTaskSummaryMapper summaryMapper;
    private final EvalPromptMapper promptMapper;
    private final EvalJudgeResultMapper judgeResultMapper;
    private final EvalTaskPromptMapper taskPromptMapper;
    private final EvalTaskModelMapper taskModelMapper;
    private final LlmApiClient llmApiClient;
    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PromptGenerator promptGenerator;
    private final DatasetAdapter datasetAdapter;
    private final LlmJudgeMetric llmJudgeMetric;

    @KafkaListener(topics = KafkaTopic.EVAL_TASK_JUDGE, groupId = "judge-worker-group")
    public void onJudgeTask(String taskIdStr) {
        Long taskId = Long.parseLong(taskIdStr);
        log.info("收到Judge评测任务: taskId={}", taskId);

        // 0. 获取任务（含字段映射）
        EvalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在: taskId={}", taskId);
            return;
        }

        // 1. 获取任务关联的评测Prompt
        List<EvalTaskPrompt> taskPrompts = taskPromptMapper.selectList(
                new LambdaQueryWrapper<EvalTaskPrompt>().eq(EvalTaskPrompt::getTaskId, taskId));
        List<EvalPrompt> prompts = new ArrayList<>();
        for (EvalTaskPrompt tp : taskPrompts) {
            // 优先使用任务创建时冻结的快照（评估器后续编辑不影响本任务）；旧任务无快照时回退当前表
            if (StrUtil.isNotBlank(tp.getPromptTemplate()) || StrUtil.isNotBlank(tp.getDimensionsConfig())) {
                EvalPrompt p = new EvalPrompt();
                p.setId(tp.getPromptId());
                p.setName(StrUtil.blankToDefault(tp.getPromptName(), ""));
                p.setPromptTemplate(tp.getPromptTemplate());
                p.setDimensionsConfig(tp.getDimensionsConfig());
                p.setStatus(1);
                prompts.add(p);
            } else {
                EvalPrompt p = promptMapper.selectById(tp.getPromptId());
                if (p != null) prompts.add(p);
            }
        }
        if (prompts.isEmpty()) {
            log.warn("无评测Prompt: taskId={}", taskId);
            return;
        }

        // 2. 获取Judge模型：优先用任务指定的裁判模型，否则自动选第一个可用
        EvalModelConfig judgeModel = null;
        if (task.getJudgeModelId() != null) {
            judgeModel = modelConfigMapper.selectById(task.getJudgeModelId());
            if (judgeModel == null || judgeModel.getStatus() != 1) {
                log.error("任务指定的裁判模型不可用: taskId={}, judgeModelId={}", taskId, task.getJudgeModelId());
                task.setStatus(TaskStatus.FAILED);
                taskMapper.updateById(task);
                return;
            }
        } else {
            List<EvalModelConfig> models = modelConfigMapper.selectList(
                    new LambdaQueryWrapper<EvalModelConfig>().eq(EvalModelConfig::getStatus, 1)
                            .orderByDesc(EvalModelConfig::getId));
            if (models.isEmpty()) { log.warn("无可用Judge模型"); return; }
            judgeModel = models.get(0);
        }
        log.info("使用模型 {} 作为Judge", judgeModel.getName());

        // 3. 获取所有评测结果（被测模型回复）
        List<EvalResult> results = resultMapper.selectList(
                new LambdaQueryWrapper<EvalResult>().eq(EvalResult::getTaskId, taskId));

        // 4. 为每条结果 × 每个评测Prompt 创建判定记录并执行
        int judgedCount = 0;
        int totalCount = results.size() * prompts.size();
        String progressKey = "eval:task:" + taskId + ":judge-progress";

        // 初始化 Judge 进度
        redisTemplate.opsForHash().putAll(progressKey, Map.of(
                "TOTAL", String.valueOf(totalCount),
                "COMPLETED", "0"
        ));
        redisTemplate.expire(progressKey, 7, java.util.concurrent.TimeUnit.DAYS);

        for (EvalResult result : results) {
            EvalDatasetItem item = datasetItemMapper.selectById(result.getDatasetItemId());

            for (EvalPrompt prompt : prompts) {
                // 渲染评测Prompt
                java.util.Map<String, String> mapping = parseFieldMapping(task.getFieldMapping());

                // 优先用 dimensions_config 自动生成的模板，否则用手动写的 prompt_template
                PromptGenerator.DimensionsConfig dimConfig = null;
                if (StrUtil.isNotBlank(prompt.getDimensionsConfig())) {
                    dimConfig = PromptGenerator.DimensionsConfig.fromJson(prompt.getDimensionsConfig());
                }

                if (dimConfig != null && dimConfig.getDimensions() != null && !dimConfig.getDimensions().isEmpty()) {
                    // ===== 结构化模式：每个维度单独调用裁判模型 =====
                    // 每个维度一条记录，先创建所有维度记录，再逐个执行
                    Map<Integer, EvalJudgeResult> dimRecords = new LinkedHashMap<>();
                    List<PromptGenerator.DimensionDef> dims = dimConfig.getDimensions();
                    for (int i = 0; i < dims.size(); i++) {
                        EvalJudgeResult jr = new EvalJudgeResult();
                        jr.setTaskId(taskId);
                        jr.setModelConfigId(result.getModelConfigId());
                        jr.setDatasetItemId(result.getDatasetItemId());
                        jr.setPromptId(prompt.getId());
                        jr.setDimension(dims.get(i).getName());
                        jr.setJudgeStatus(JudgeStatus.PENDING);
                        judgeResultMapper.insert(jr);
                        dimRecords.put(i, jr);
                    }

                    if (item == null) {
                        for (EvalJudgeResult jr : dimRecords.values()) {
                            jr.setJudgeStatus(JudgeStatus.SKIP);
                            judgeResultMapper.updateById(jr);
                        }
                        continue;
                    }

                    int dimBadcaseCount = 0;
                    int dimUnknownCount = 0;
                    int dimJudged = 0;
                    // 归一化为统一样本（被测模型回答从 result.response 读取）
                    EvalSample sample = datasetAdapter.toSample(item, result.getResponse());

                    for (int i = 0; i < dims.size(); i++) {
                        EvalJudgeResult jr = dimRecords.get(i);
                        PromptGenerator.DimensionDef dim = dims.get(i);
                        try {
                            // 通过 LlmJudgeMetric 完成单维度判定（生成→渲染→调用→解析）
                            Map<String, Object> metricConfig = new HashMap<>();
                            metricConfig.put("judgeModel", judgeModel);
                            metricConfig.put("dimensionsConfig", dimConfig);
                            metricConfig.put("dimension", dim);
                            metricConfig.put("fieldMapping", mapping);
                            MetricResult mr = llmJudgeMetric.evaluate(sample, metricConfig);

                            // 三态：true=badcase / false=goodcase / null=unknown（AI无法判断）
                            Boolean bad = mr.getBadcase();
                            jr.setIsBadcase(bad == null ? null : (bad ? 1 : 0));
                            jr.setReason(mr.getReason());
                            jr.setJudgeStatus(JudgeStatus.JUDGED);
                            judgeResultMapper.updateById(jr);
                            dimJudged++;
                            if (bad == null) {
                                dimUnknownCount++;
                            } else if (bad) {
                                dimBadcaseCount++;
                            }
                        } catch (Exception e) {
                            log.error("单维度评测失败: jrId={}, dim={}, itemId={}", jr.getId(), dim.getName(), result.getDatasetItemId(), e);
                            jr.setJudgeStatus(JudgeStatus.SKIP);
                            jr.setIsBadcase(null);
                            jr.setReason("AI结果解析失败(已跳过): " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                            judgeResultMapper.updateById(jr);
                        }
                    }

                    // 聚合生成整体判定记录（dimension=NULL），按 badcase_rule 计算，支持三态
                    EvalJudgeResult overallJr = new EvalJudgeResult();
                    overallJr.setTaskId(taskId);
                    overallJr.setModelConfigId(result.getModelConfigId());
                    overallJr.setDatasetItemId(result.getDatasetItemId());
                    overallJr.setPromptId(prompt.getId());
                    overallJr.setDimension(null);
                    if (dimJudged > 0) {
                        boolean overallBad = promptGenerator.applyBadcaseRule(dimBadcaseCount, dimJudged, dimConfig.getBadcaseRule());
                        if (overallBad) {
                            overallJr.setIsBadcase(1);
                            overallJr.setJudgeStatus(JudgeStatus.JUDGED);
                            overallJr.setReason(String.format("共%d个维度，%d个判定为badcase，%d个无法判断（规则:%s）",
                                    dimJudged, dimBadcaseCount, dimUnknownCount, dimConfig.getBadcaseRule()));
                        } else if (dimUnknownCount > 0) {
                            // 未触发 badcase 规则，但存在 unknown 维度 → 整体 unknown
                            overallJr.setIsBadcase(null);
                            overallJr.setJudgeStatus(JudgeStatus.JUDGED);
                            overallJr.setReason(String.format("未触发badcase规则，但%d个维度无法判断，整体结论为unknown（规则:%s）",
                                    dimUnknownCount, dimConfig.getBadcaseRule()));
                        } else {
                            overallJr.setIsBadcase(0);
                            overallJr.setJudgeStatus(JudgeStatus.JUDGED);
                            overallJr.setReason(String.format("共%d个维度，%d个判定为badcase，%d个无法判断（规则:%s）",
                                    dimJudged, dimBadcaseCount, dimUnknownCount, dimConfig.getBadcaseRule()));
                        }
                        // 记录触发 badcase 的维度列表
                        List<String> badDims = new ArrayList<>();
                        for (int i = 0; i < dims.size(); i++) {
                            EvalJudgeResult dr = dimRecords.get(i);
                            if (dr.getIsBadcase() != null && dr.getIsBadcase() == 1) {
                                badDims.add(dims.get(i).getName());
                            }
                        }
                        overallJr.setDimensions(JSONUtil.toJsonStr(badDims));
                    } else {
                        overallJr.setJudgeStatus(JudgeStatus.SKIP);
                        overallJr.setIsBadcase(null);
                        overallJr.setReason("所有维度均判定失败(已跳过)");
                    }
                    judgeResultMapper.insert(overallJr);
                    judgedCount++;
                    redisTemplate.opsForHash().increment(progressKey, "COMPLETED", 1);

                } else {
                    // ===== 旧模式（自由文本 prompt）：一次调用，整体判定 =====
                    EvalJudgeResult jr = new EvalJudgeResult();
                    jr.setTaskId(taskId);
                    jr.setModelConfigId(result.getModelConfigId());
                    jr.setDatasetItemId(result.getDatasetItemId());
                    jr.setPromptId(prompt.getId());
                    jr.setDimension(null);
                    jr.setJudgeStatus(JudgeStatus.PENDING);
                    judgeResultMapper.insert(jr);

                    if (item == null) {
                        jr.setJudgeStatus(JudgeStatus.SKIP);
                        judgeResultMapper.updateById(jr);
                        continue;
                    }

                    try {
                        String promptTemplate = prompt.getPromptTemplate();
                        if (StrUtil.isBlank(promptTemplate)) {
                            throw new IllegalStateException("评估Prompt为空");
                        }

                        // 用 DatasetAdapter 渲染（自由文本 prompt，被测模型回答写入 sample.modelResponse）
                        EvalSample legacySample = datasetAdapter.toSample(item, result.getResponse());
                        String renderedPrompt = datasetAdapter.renderPrompt(promptTemplate, legacySample, mapping);

                        // 拆分 system/user
                        String[] parts = splitSystemUser(renderedPrompt);
                        String systemPart = parts[0];
                        String userPart = parts[1];

                        LlmApiClient.ChatResponse chatResponse;
                        if (systemPart != null && !systemPart.isEmpty()) {
                            chatResponse = llmApiClient.chatWithUsage(judgeModel, systemPart, userPart);
                        } else {
                            chatResponse = llmApiClient.chatWithUsage(judgeModel, null, renderedPrompt);
                        }
                        String aiResponse = chatResponse.getContent();

                        // 解析AI返回的JSON
                        parseAiResponseLegacy(jr, aiResponse);

                        // 记录 Prompt Cache 命中情况
                        jr.setCachedTokens(chatResponse.getCachedTokens());
                        jr.setJudgeStatus(JudgeStatus.JUDGED);
                        judgeResultMapper.updateById(jr);
                        judgedCount++;
                        redisTemplate.opsForHash().increment(progressKey, "COMPLETED", 1);

                    } catch (Exception e) {
                        log.error("Judge评测失败: jrId={}, itemId={}", jr.getId(), result.getDatasetItemId(), e);
                        jr.setJudgeStatus(JudgeStatus.SKIP);
                        jr.setIsBadcase(null);
                        jr.setReason("AI结果解析失败(已跳过): " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                        judgeResultMapper.updateById(jr);
                    }
                }
            }
        }

        // 5. 计算汇总统计
        computeSummary(taskId);
        log.info("Judge评测完成: taskId={}, judged={}/{}", taskId, judgedCount, totalCount);
    }

    /**
     * 拆分 system / user 以利用 Prompt Cache（前缀缓存）
     * 策略1: 用户手动用 ---CASE--- 分隔（最精确）
     * 策略2: 自动识别，第一个 ${xxx} 或 {xxx} 之前的内容作为 system 前缀
     * 策略3: 无占位符的 prompt 整体作为 user（兼容旧 prompt）
     */
    private String[] splitSystemUser(String renderedPrompt) {
        String systemPart = null;
        String userPart = renderedPrompt;

        // 策略1: 手动分隔线
        int sepIndex = renderedPrompt.indexOf("---CASE---");
        if (sepIndex >= 0) {
            systemPart = renderedPrompt.substring(0, sepIndex).trim();
            userPart = renderedPrompt.substring(sepIndex + "---CASE---".length()).trim();
            return new String[]{systemPart, userPart};
        }

        // 策略2: 自动识别第一个占位符位置
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{[a-zA-Z_][a-zA-Z0-9_:]*\\}|\\{[a-zA-Z_][a-zA-Z0-9_:]*\\}").matcher(renderedPrompt);
        if (m.find()) {
            int firstPlaceholderStart = m.start();
            if (firstPlaceholderStart > 0) {
                // 找到第一个占位符之前的最后一个完整段落边界（\n\n 或行首）
                String beforePlaceholder = renderedPrompt.substring(0, firstPlaceholderStart);
                // 回退到最后一个双换行，确保 system 部分是完整段落
                int lastDoubleNewline = beforePlaceholder.lastIndexOf("\n\n");
                int lastSingleNewline = beforePlaceholder.lastIndexOf("\n");
                int cutPoint = 0;
                if (lastDoubleNewline > 0) {
                    cutPoint = lastDoubleNewline;
                } else if (lastSingleNewline > 50) {
                    // 没有双换行，但有单换行且前文足够长（>50字符有意义）
                    cutPoint = lastSingleNewline;
                }
                if (cutPoint > 0) {
                    systemPart = renderedPrompt.substring(0, cutPoint).trim();
                    userPart = renderedPrompt.substring(cutPoint).trim();
                }
            }
        }
        return new String[]{systemPart, userPart};
    }

    /**
     * 旧模式兼容解析：硬编码 key 名
     * 解析失败时抛异常（不再静默当作 goodcase）
     */
    private void parseAiResponseLegacy(EvalJudgeResult jr, String aiResponse) {
        String jsonStr = aiResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }

        JSONObject jsonObj = JSONUtil.parseObj(jsonStr); // 抛异常由调用方处理

        // 解析 is_badcase：兼容多种写法；null/unknown → 整体 unknown（judge_status 仍 JUDGED）
        Integer verdict = null;
        if (jsonObj.containsKey("is_badcase")) {
            verdict = parseLegacyVerdict(jsonObj.get("is_badcase"));
        } else if (jsonObj.containsKey("badcase")) {
            verdict = parseLegacyVerdict(jsonObj.get("badcase"));
        } else {
            // 旧行为：未显式输出 is_badcase 默认 goodcase
            verdict = 0;
        }
        jr.setIsBadcase(verdict);

        // 解析 dimensions：兼容数组 ["准确性","完整性"] 或对象 {"准确性":{...},"完整性":{...}} 两种格式
        String dimsJson = "[]";
        Object dimsVal = jsonObj.get("dimensions");
        if (dimsVal instanceof JSONArray) {
            // 数组格式：badcase 维度名列表
            JSONArray dimArr = (JSONArray) dimsVal;
            if (!dimArr.isEmpty()) dimsJson = dimArr.toString();
        } else if (dimsVal instanceof JSONObject) {
            // 对象格式：每个维度一个结果对象，如 {"准确性":{"score":5,"reason":"..."}}
            JSONObject dimObj = (JSONObject) dimsVal;
            JSONArray badDims = new JSONArray();
            for (String key : dimObj.keySet()) {
                Object v = dimObj.get(key);
                if (v instanceof JSONObject) {
                    JSONObject dimRes = (JSONObject) v;
                    boolean dimBad = false;
                    if (dimRes.containsKey("is_badcase")) {
                        dimBad = dimRes.getBool("is_badcase", false);
                    } else if (dimRes.containsKey("badcase")) {
                        Object bv = dimRes.get("badcase");
                        if (bv instanceof Boolean) dimBad = (Boolean) bv;
                        else if (bv != null) {
                            String s = bv.toString().trim();
                            dimBad = "是".equals(s) || "true".equalsIgnoreCase(s) || "1".equals(s);
                        }
                    } else if (dimRes.containsKey("score") && dimRes.get("score") != null) {
                        // score 类型：低于 3 分视为 badcase（与通用评估器阈值一致）
                        try {
                            int score = dimRes.getInt("score", 0);
                            dimBad = score < 3;
                        } catch (Exception ignored) {}
                    } else if (dimRes.containsKey("result") && dimRes.get("result") != null) {
                        // 布尔/枚举类型：result=false 或 含"不采纳/否/B/差"视为 badcase
                        String rs = dimRes.get("result").toString().trim();
                        dimBad = "false".equalsIgnoreCase(rs) || "否".equals(rs)
                                || "不采纳".equals(rs) || "B".equals(rs) || "差".equals(rs);
                    }
                    if (dimBad) badDims.add(key);
                }
            }
            if (!badDims.isEmpty()) dimsJson = badDims.toString();
        }
        jr.setDimensions(dimsJson);

        // 解析 reason
        String reason = "";
        if (jsonObj.containsKey("reason")) reason = jsonObj.getStr("reason", "");
        jr.setReason(reason);

        // 存原始结果
        jr.setParsedResult(jsonStr);
    }

    /**
     * 解析旧模式整体判定为三态：
     * 1=badcase / 0=goodcase / null=unknown（AI 明示无法判断时）
     */
    private Integer parseLegacyVerdict(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Boolean) return ((Boolean) raw) ? 1 : 0;
        if (raw instanceof Number) return ((Number) raw).intValue() == 0 ? 0 : 1;
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        if ("unknown".equalsIgnoreCase(s) || "unable".equalsIgnoreCase(s) || "unclear".equalsIgnoreCase(s)
                || "不确定".equals(s) || "无法判断".equals(s) || "无法确定".equals(s) || "无法判定".equals(s)
                || "资料不足".equals(s) || "信息不足".equals(s) || "未知".equals(s)) {
            return null;
        }
        if ("是".equals(s) || "true".equalsIgnoreCase(s) || "1".equals(s)) return 1;
        return 0;
    }

    /**
     * 计算汇总统计：整体badcase率 + 按维度badcase率
     * 新结构：dimension=NULL 为整体判定记录，dimension!=NULL 为单维度判定记录
     */
    private void computeSummary(Long taskId) {
        List<EvalTaskPrompt> taskPrompts = taskPromptMapper.selectList(
                new LambdaQueryWrapper<EvalTaskPrompt>().eq(EvalTaskPrompt::getTaskId, taskId));
        List<EvalTaskModel> taskModels = taskModelMapper.selectList(
                new LambdaQueryWrapper<EvalTaskModel>().eq(EvalTaskModel::getTaskId, taskId));

        for (EvalTaskModel tm : taskModels) {
            Long modelId = tm.getModelConfigId();

            List<EvalResult> modelResults = resultMapper.selectList(
                    new LambdaQueryWrapper<EvalResult>()
                            .eq(EvalResult::getTaskId, taskId)
                            .eq(EvalResult::getModelConfigId, modelId));
            if (modelResults.isEmpty()) continue;

            double avgLatency = modelResults.stream().filter(r -> r.getLatencyMs() != null).mapToInt(EvalResult::getLatencyMs).average().orElse(0.0);
            long totalTokens = modelResults.stream().filter(r -> r.getTokenUsage() != null).mapToLong(EvalResult::getTokenUsage).sum();

            for (EvalTaskPrompt tp : taskPrompts) {
                Long promptId = tp.getPromptId();

                // ===== 整体判定记录（dimension IS NULL）=====
                List<EvalJudgeResult> overallResults = judgeResultMapper.selectList(
                        new LambdaQueryWrapper<EvalJudgeResult>()
                                .eq(EvalJudgeResult::getTaskId, taskId)
                                .eq(EvalJudgeResult::getModelConfigId, modelId)
                                .eq(EvalJudgeResult::getPromptId, promptId)
                                .isNull(EvalJudgeResult::getDimension)
                                .eq(EvalJudgeResult::getJudgeStatus, JudgeStatus.JUDGED));

                // ===== 单维度判定记录（dimension NOT NULL）=====
                List<EvalJudgeResult> dimResults = judgeResultMapper.selectList(
                        new LambdaQueryWrapper<EvalJudgeResult>()
                                .eq(EvalJudgeResult::getTaskId, taskId)
                                .eq(EvalJudgeResult::getModelConfigId, modelId)
                                .eq(EvalJudgeResult::getPromptId, promptId)
                                .isNotNull(EvalJudgeResult::getDimension)
                                .eq(EvalJudgeResult::getJudgeStatus, JudgeStatus.JUDGED));

                // 统计无法判定的条目数（SKIP，如AI输出无法解析）
                long skipCount = judgeResultMapper.selectCount(
                        new LambdaQueryWrapper<EvalJudgeResult>()
                                .eq(EvalJudgeResult::getTaskId, taskId)
                                .eq(EvalJudgeResult::getModelConfigId, modelId)
                                .eq(EvalJudgeResult::getPromptId, promptId)
                                .eq(EvalJudgeResult::getJudgeStatus, JudgeStatus.SKIP));

                if (overallResults.isEmpty() && dimResults.isEmpty() && skipCount == 0) continue;

                // ===== 整体汇总（用整体判定记录）=====
                // JUDGED 记录三态：isBadcase 1/0/null(unknown)
                int judgedTotal = overallResults.size();
                int totalBadcase = (int) overallResults.stream().filter(j -> j.getIsBadcase() != null && j.getIsBadcase() == 1).count();
                int totalGood = (int) overallResults.stream().filter(j -> j.getIsBadcase() != null && j.getIsBadcase() == 0).count();
                int totalUnknown = judgedTotal - totalBadcase - totalGood;

                EvalTaskSummary overall = new EvalTaskSummary();
                overall.setTaskId(taskId);
                overall.setModelConfigId(modelId);
                overall.setPromptId(promptId);
                overall.setDimension(null);
                overall.setTotalCount(judgedTotal);
                overall.setGoodCount(totalGood);
                overall.setBadcaseCount(totalBadcase);
                overall.setUnknownCount(totalUnknown);
                overall.setSkipCount((int) skipCount);
                overall.setBadcaseRate(judgedTotal > 0
                        ? BigDecimal.valueOf(totalBadcase * 100.0 / judgedTotal).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO);
                overall.setAvgLatencyMs((int) avgLatency);
                overall.setTotalTokenUsage(totalTokens);
                upsertSummary(overall, taskId, modelId, promptId, null);

                // ===== 按维度汇总 =====
                // 单维度模式：直接用 dimension 字段分组统计
                Map<String, List<EvalJudgeResult>> dimGroup = dimResults.stream()
                        .filter(j -> StrUtil.isNotBlank(j.getDimension()))
                        .collect(Collectors.groupingBy(EvalJudgeResult::getDimension));

                // 旧模式降级：如果本 prompt 没有单维度记录（自由文本 prompt 只有整体记录），
                // 则从整体记录的 dimensions 字段（badcase 维度名列表）解析维度统计
                if (dimGroup.isEmpty() && !overallResults.isEmpty()) {
                    // 先判断本 prompt 是否结构化（有 dimensions_config 则单维度模式，此时不该走到这里）
                    // 优先用任务快照判断，旧任务回退当前表
                    boolean structured = StrUtil.isNotBlank(tp.getDimensionsConfig());
                    if (!structured) {
                        EvalPrompt prompt = promptMapper.selectById(promptId);
                        structured = prompt != null && StrUtil.isNotBlank(prompt.getDimensionsConfig());
                    }
                    if (!structured) {
                        Map<String, int[]> legacyStats = new LinkedHashMap<>();
                        for (EvalJudgeResult jr : overallResults) {
                            countDimBadcaseLegacy(jr, legacyStats, overallResults.size());
                        }
                        for (Map.Entry<String, int[]> entry : legacyStats.entrySet()) {
                            String dim = entry.getKey();
                            int dimBadcase = entry.getValue()[0];
                            int dimTotal = entry.getValue()[1];
                            EvalTaskSummary dimSummary = new EvalTaskSummary();
                            dimSummary.setTaskId(taskId);
                            dimSummary.setModelConfigId(modelId);
                            dimSummary.setPromptId(promptId);
                            dimSummary.setDimension(dim);
                            dimSummary.setTotalCount(dimTotal);
                            dimSummary.setGoodCount(dimTotal - dimBadcase);
                            dimSummary.setBadcaseCount(dimBadcase);
                            dimSummary.setUnknownCount(0);
                            dimSummary.setBadcaseRate(dimTotal > 0
                                    ? BigDecimal.valueOf(dimBadcase * 100.0 / dimTotal).setScale(2, RoundingMode.HALF_UP)
                                    : BigDecimal.ZERO);
                            dimSummary.setAvgLatencyMs((int) avgLatency);
                            dimSummary.setTotalTokenUsage(totalTokens);
                            upsertSummary(dimSummary, taskId, modelId, promptId, dim);
                        }
                        continue;
                    }
                }

                for (Map.Entry<String, List<EvalJudgeResult>> entry : dimGroup.entrySet()) {
                    String dim = entry.getKey();
                    List<EvalJudgeResult> dimList = entry.getValue();
                    int dimTotal = dimList.size();
                    int dimBadcase = (int) dimList.stream().filter(j -> j.getIsBadcase() != null && j.getIsBadcase() == 1).count();
                    int dimGood = (int) dimList.stream().filter(j -> j.getIsBadcase() != null && j.getIsBadcase() == 0).count();
                    int dimUnknown = dimTotal - dimBadcase - dimGood;

                    EvalTaskSummary dimSummary = new EvalTaskSummary();
                    dimSummary.setTaskId(taskId);
                    dimSummary.setModelConfigId(modelId);
                    dimSummary.setPromptId(promptId);
                    dimSummary.setDimension(dim);
                    dimSummary.setTotalCount(dimTotal);
                    dimSummary.setGoodCount(dimGood);
                    dimSummary.setBadcaseCount(dimBadcase);
                    dimSummary.setUnknownCount(dimUnknown);
                    dimSummary.setBadcaseRate(dimTotal > 0
                            ? BigDecimal.valueOf(dimBadcase * 100.0 / dimTotal).setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO);
                    dimSummary.setAvgLatencyMs((int) avgLatency);
                    dimSummary.setTotalTokenUsage(totalTokens);
                    upsertSummary(dimSummary, taskId, modelId, promptId, dim);
                }
            }
        }
    }

    /**
     * 旧模式维度统计：dimensions 字段存的是 badcase 维度名列表
     * 分母用 judgedTotal（所有样本都参与了评测），分子用该维度被触发的次数
     */
    private void countDimBadcaseLegacy(EvalJudgeResult jr, Map<String, int[]> dimStats, int judgedTotal) {
        if (StrUtil.isBlank(jr.getDimensions()) || "[]".equals(jr.getDimensions())) return;
        try {
            JSONArray dimArr = JSONUtil.parseArray(jr.getDimensions());
            for (int i = 0; i < dimArr.size(); i++) {
                String dim = dimArr.getStr(i);
                if (StrUtil.isNotBlank(dim)) {
                    dimStats.putIfAbsent(dim, new int[]{0, judgedTotal});
                    if (jr.getIsBadcase() != null && jr.getIsBadcase() == 1) {
                        dimStats.get(dim)[0]++;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void upsertSummary(EvalTaskSummary summary, Long taskId, Long modelId, Long promptId, String dimension) {
        LambdaQueryWrapper<EvalTaskSummary> wrapper = new LambdaQueryWrapper<EvalTaskSummary>()
                .eq(EvalTaskSummary::getTaskId, taskId)
                .eq(EvalTaskSummary::getModelConfigId, modelId)
                .eq(EvalTaskSummary::getPromptId, promptId);

        if (dimension != null) {
            wrapper.eq(EvalTaskSummary::getDimension, dimension);
        } else {
            wrapper.isNull(EvalTaskSummary::getDimension);
        }

        EvalTaskSummary existing = summaryMapper.selectOne(wrapper);
        if (existing != null) {
            summary.setId(existing.getId());
            summaryMapper.updateById(summary);
        } else {
            summaryMapper.insert(summary);
        }
    }

    /** 解析字段映射JSON为Map<占位符key, 数据集字段名>（下沉到 DatasetAdapter） */
    private java.util.Map<String, String> parseFieldMapping(String fieldMappingJson) {
        return datasetAdapter.parseFieldMapping(fieldMappingJson);
    }
}
