package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.auth.AuthContext;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.common.util.OwnershipUtil;
import com.eval.dao.mapper.EvalModelConfigMapper;
import com.eval.dao.mapper.EvalPromptMapper;
import com.eval.dao.mapper.EvalPromptVersionMapper;
import com.eval.model.dto.PromptDTO;
import com.eval.model.dto.NameCheckResult;
import com.eval.model.entity.EvalModelConfig;
import com.eval.model.entity.EvalPrompt;
import com.eval.model.entity.EvalPromptVersion;
import com.eval.service.LlmApiClient;
import com.eval.service.PromptGenerator;
import com.eval.service.PromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class PromptServiceImpl implements PromptService {

    private final EvalPromptMapper promptMapper;
    private final EvalPromptVersionMapper promptVersionMapper;
    private final PromptGenerator promptGenerator;
    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;

    @Override
    public NameCheckResult checkName(String name, Long excludePromptId) {
        NameCheckResult result = new NameCheckResult();
        if (StrUtil.isBlank(name)) {
            return result;
        }
        EvalPrompt existing = promptMapper.selectOne(
                new LambdaQueryWrapper<EvalPrompt>()
                        .eq(EvalPrompt::getName, name.trim())
                        .ne(excludePromptId != null, EvalPrompt::getId, excludePromptId)
                        .last("LIMIT 1"));
        result.setExists(existing != null);
        result.setVersionCount(existing != null ? 1 : 0);
        result.setLatestVersion(existing != null && existing.getVersion() != null ? existing.getVersion() : 0);
        result.setNextVersion(1);
        result.setTargetVersionTaken(existing != null);
        return result;
    }

    @Override
    public EvalPrompt add(PromptDTO dto, Long createdBy) {
        // 名称全局唯一：重复名称直接拒绝
        NameCheckResult check = checkName(dto.getName(), null);
        if (check.isExists()) {
            throw new BusinessException("评估器名称「" + dto.getName().trim() + "」已存在，请更换名称");
        }
        EvalPrompt prompt = new EvalPrompt();
        prompt.setName(dto.getName());
        prompt.setDescription(dto.getDescription() != null ? dto.getDescription() : "");
        prompt.setDimensionsConfig(dto.getDimensionsConfig());
        prompt.setEvaluationMode("reference".equals(dto.getEvaluationMode()) ? "reference" : "quality");

        // 如果有 dimensionsConfig，自动生成 promptTemplate
        if (StrUtil.isNotBlank(dto.getDimensionsConfig())) {
            PromptGenerator.DimensionsConfig config = PromptGenerator.DimensionsConfig.fromJson(dto.getDimensionsConfig());
            if (config != null && config.getDimensions() != null && !config.getDimensions().isEmpty()) {
                prompt.setPromptTemplate(promptGenerator.generatePrompt(config));
            } else {
                prompt.setPromptTemplate(dto.getPromptTemplate() != null ? dto.getPromptTemplate() : "");
            }
        } else {
            // 旧模式：必须手动填 promptTemplate
            if (StrUtil.isBlank(dto.getPromptTemplate())) {
                throw new BusinessException("非结构化模式下，评测Prompt不能为空");
            }
            prompt.setPromptTemplate(dto.getPromptTemplate());
        }

        prompt.setStatus(1);
        prompt.setVersion(1);
        prompt.setCreatedBy(createdBy);
        prompt.setCreatedAt(LocalDateTime.now());
        promptMapper.insert(prompt);
        log.info("评测Prompt创建成功: id={}, name={}", prompt.getId(), prompt.getName());
        return prompt;
    }

    @Override
    public PageResult<EvalPrompt> list(int page, int size) {
        Page<EvalPrompt> pageObj = new Page<>(page, size);
        Page<EvalPrompt> result = promptMapper.selectPage(pageObj,
                new LambdaQueryWrapper<EvalPrompt>().orderByDesc(EvalPrompt::getCreatedAt));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public EvalPrompt update(Long id, PromptDTO dto) {
        EvalPrompt prompt = promptMapper.selectById(id);
        if (prompt == null) throw new BusinessException("Prompt不存在");

        // 计算目标值
        String newName = dto.getName();
        // 名称唯一性校验：改名后与他人重名则拒绝（排除自身）
        if (StrUtil.isNotBlank(newName)) {
            NameCheckResult check = checkName(newName, id);
            if (check.isExists()) {
                throw new BusinessException("评估器名称「" + newName.trim() + "」已被其他评估器使用，请更换名称");
            }
        }
        String newDesc = dto.getDescription() != null ? dto.getDescription() : "";
        String newDimCfg = dto.getDimensionsConfig();
        String newMode = "reference".equals(dto.getEvaluationMode()) ? "reference" : "quality";
        String newTemplate;
        if (StrUtil.isNotBlank(newDimCfg)) {
            PromptGenerator.DimensionsConfig config = PromptGenerator.DimensionsConfig.fromJson(newDimCfg);
            if (config != null && config.getDimensions() != null && !config.getDimensions().isEmpty()) {
                newTemplate = promptGenerator.generatePrompt(config);
            } else {
                newTemplate = dto.getPromptTemplate() != null ? dto.getPromptTemplate() : "";
            }
        } else {
            if (StrUtil.isBlank(dto.getPromptTemplate())) {
                throw new BusinessException("非结构化模式下，评测Prompt不能为空");
            }
            newTemplate = dto.getPromptTemplate();
        }

        // 内容是否有实际变化（决定是否升版本）
        boolean changed = !Objects.equals(newName, prompt.getName())
                || !Objects.equals(newDesc, StrUtil.nullToEmpty(prompt.getDescription()))
                || !Objects.equals(newDimCfg, prompt.getDimensionsConfig())
                || !Objects.equals(newMode, prompt.getEvaluationMode())
                || !Objects.equals(newTemplate, prompt.getPromptTemplate());

        if (changed) {
            // 把旧版本快照到 eval_prompt_version，再升版本号
            int oldVersion = prompt.getVersion() != null ? prompt.getVersion() : 1;
            EvalPromptVersion snapshot = new EvalPromptVersion();
            snapshot.setPromptId(prompt.getId());
            snapshot.setVersion(oldVersion);
            snapshot.setName(prompt.getName());
            snapshot.setDescription(StrUtil.nullToEmpty(prompt.getDescription()));
            snapshot.setPromptTemplate(prompt.getPromptTemplate());
            snapshot.setDimensionsConfig(prompt.getDimensionsConfig());
            snapshot.setEvaluationMode(prompt.getEvaluationMode());
            snapshot.setCreatedAt(LocalDateTime.now());
            promptVersionMapper.insert(snapshot);
            prompt.setVersion(oldVersion + 1);
            log.info("评估器版本化: id={}, v{} -> v{}", prompt.getId(), oldVersion, oldVersion + 1);
        }

        prompt.setName(newName);
        prompt.setDescription(newDesc);
        prompt.setDimensionsConfig(newDimCfg);
        prompt.setEvaluationMode(newMode);
        prompt.setPromptTemplate(newTemplate);

        promptMapper.updateById(prompt);
        log.info("评测Prompt更新成功: id={}, name={}", prompt.getId(), prompt.getName());
        return prompt;
    }

    @Override
    public List<EvalPromptVersion> listVersions(Long promptId) {
        return promptVersionMapper.selectList(
                new LambdaQueryWrapper<EvalPromptVersion>()
                        .eq(EvalPromptVersion::getPromptId, promptId)
                        .orderByDesc(EvalPromptVersion::getVersion));
    }

    @Override
    public EvalPromptVersion getVersion(Long promptId, Integer version) {
        return promptVersionMapper.selectOne(
                new LambdaQueryWrapper<EvalPromptVersion>()
                        .eq(EvalPromptVersion::getPromptId, promptId)
                        .eq(EvalPromptVersion::getVersion, version));
    }

    @Override
    public EvalPrompt restoreVersion(Long promptId, Integer version) {
        // 1. 查历史快照
        EvalPromptVersion snapshot = getVersion(promptId, version);
        if (snapshot == null) throw new BusinessException("历史版本不存在");

        // 2. 查当前评估器
        EvalPrompt prompt = promptMapper.selectById(promptId);
        if (prompt == null) throw new BusinessException("评估器不存在");

        // 3. 把当前版本快照（防止丢失当前未版本化的内容）
        EvalPromptVersion currentSnapshot = new EvalPromptVersion();
        currentSnapshot.setPromptId(prompt.getId());
        currentSnapshot.setVersion(prompt.getVersion());
        currentSnapshot.setName(prompt.getName());
        currentSnapshot.setDescription(StrUtil.nullToEmpty(prompt.getDescription()));
        currentSnapshot.setPromptTemplate(prompt.getPromptTemplate());
        currentSnapshot.setDimensionsConfig(prompt.getDimensionsConfig());
        currentSnapshot.setEvaluationMode(prompt.getEvaluationMode());
        currentSnapshot.setCreatedAt(LocalDateTime.now());
        promptVersionMapper.insert(currentSnapshot);

        // 4. 用快照内容覆盖当前评估器，版本号自增
        prompt.setName(snapshot.getName());
        prompt.setDescription(snapshot.getDescription());
        prompt.setPromptTemplate(snapshot.getPromptTemplate());
        prompt.setDimensionsConfig(snapshot.getDimensionsConfig());
        prompt.setEvaluationMode(snapshot.getEvaluationMode());
        prompt.setVersion(prompt.getVersion() + 1);
        promptMapper.updateById(prompt);

        log.info("评估器版本恢复: id={}, 恢复到 v{}, 当前升至 v{}", promptId, version, prompt.getVersion());
        return prompt;
    }

    @Override
    public void deleteById(Long id, AuthContext ctx) {
        EvalPrompt prompt = promptMapper.selectById(id);
        if (prompt == null) throw new BusinessException("评估器不存在");
        OwnershipUtil.assertCanDelete(prompt.getCreatedBy(), ctx, "评估器");
        promptMapper.deleteById(id);
    }

    @Override
    public EvalPrompt getById(Long id) {
        return promptMapper.selectById(id);
    }

    @Override
    public String polish(Long modelId, String dimensionsConfig) {
        return polishParallel(modelId, dimensionsConfig, null);
    }

    @Override
    public String polishParallel(Long modelId, String dimensionsConfig, java.util.function.Consumer<String> progress) {
        if (StrUtil.isBlank(dimensionsConfig)) {
            throw new BusinessException("维度配置不能为空");
        }

        // 解析维度配置
        PromptGenerator.DimensionsConfig config = PromptGenerator.DimensionsConfig.fromJson(dimensionsConfig);
        if (config == null || config.getDimensions() == null || config.getDimensions().isEmpty()) {
            throw new BusinessException("维度配置解析失败或没有维度");
        }

        // 获取润色模型
        EvalModelConfig model = modelId != null ? modelConfigMapper.selectById(modelId) : null;
        if (model == null || model.getStatus() != 1) {
            throw new BusinessException("润色模型不可用，请先在模型管理中配置并启用");
        }

        // 并行润色每个维度：保持结构不变，只优化 rubric 文案
        JSONObject resultConfig = new JSONObject();
        resultConfig.set("role", config.getRole());
        resultConfig.set("context_template", config.getContextTemplate());
        resultConfig.set("badcase_rule", config.getBadcaseRule());
        resultConfig.set("extra_instructions", config.getExtraInstructions());

        List<PromptGenerator.DimensionDef> dims = config.getDimensions();
        int n = dims.size();
        JSONArray resultDims = new JSONArray();
        JSONObject[] results = new JSONObject[n];
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);

        // 并发提交：每个维度一个任务，带序号以便按原顺序组装
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final PromptGenerator.DimensionDef dim = dims.get(i);
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                results[idx] = polishDimensionSafe(model, dim);
                if (progress != null) {
                    progress.accept(done.incrementAndGet() + "/" + n + "：" + (dim.getName() == null ? "" : dim.getName()));
                }
            }, polishExecutor));
        }

        // 等待全部完成
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        // 按原顺序组装（单个维度失败已兜底为原始配置）
        for (int i = 0; i < n; i++) {
            resultDims.add(results[i] != null ? results[i] : buildDimBase(dims.get(i)).set("rubric", toRubricArray(dims.get(i))));
        }
        resultConfig.set("dimensions", resultDims);

        return resultConfig.toString();
    }

    /** 润色线程池（3 并发，LLM 调用为 IO 密集，足够发挥并行收益且不压垮 API 限流） */
    private final java.util.concurrent.ExecutorService polishExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    /**
     * 润色单个维度（并行执行），失败时保留原始配置而不是让整个任务失败
     */
    private JSONObject polishDimensionSafe(EvalModelConfig model, PromptGenerator.DimensionDef dim) {
        try {
            return polishDimension(model, dim);
        } catch (Exception e) {
            log.warn("润色维度失败(保留原配置): dim={}", dim.getName(), e);
            JSONObject fallback = buildDimBase(dim);
            fallback.set("rubric", toRubricArray(dim));
            return fallback;
        }
    }

    /**
     * 构建维度的基础JSON（不含 rubric，由调用方设置）
     */
    private JSONObject buildDimBase(PromptGenerator.DimensionDef dim) {
        JSONObject resultDim = new JSONObject();
        resultDim.set("name", dim.getName());
        resultDim.set("scoring_type", dim.getScoringType());
        resultDim.set("badcase_threshold", dim.getBadcaseThreshold());
        if (dim.getEnumValues() != null && dim.getEnumValues().length > 0) {
            JSONArray enumArr = new JSONArray();
            for (String v : dim.getEnumValues()) {
                enumArr.add(v);
            }
            resultDim.set("enum_values", enumArr);
        }
        return resultDim;
    }

    /**
     * 润色单个维度：让 AI 优化 rubric 描述，保持 level 和 scoring_type 不变
     */
    private JSONObject polishDimension(EvalModelConfig model, PromptGenerator.DimensionDef dim) {
        // 构建润色 prompt
        StringBuilder sys = new StringBuilder();
        sys.append("你是一名专业的 AI 评测指标设计专家。用户提供评测维度定义，你需要润色其中的评分标准描述，使其更专业、更清晰、更具体。\n");
        sys.append("要求：\n");
        sys.append("1. 保持 JSON 结构、level 和 scoring_type 完全不变\n");
        sys.append("2. 只优化 rubric 中每个 level 的 desc 描述，使其更具体、可衡量、可操作\n");
        sys.append("3. desc 描述应当说明该等级下回答的具体特征、典型表现和判定要点\n");
        sys.append("4. 语言精炼，每个 desc 控制在 40-80 字以内\n");
        sys.append("5. 直接输出 JSON，不要输出多余内容\n");

        StringBuilder user = new StringBuilder();
        user.append("评测维度名称：").append(dim.getName()).append("\n");
        user.append("评分方式：").append(dim.getScoringType()).append("\n");
        if (dim.getScoringType() != null && !"score".equals(dim.getScoringType())
                && dim.getEnumValues() != null && dim.getEnumValues().length > 0) {
            user.append("可选值：").append(String.join("/", dim.getEnumValues())).append("\n");
        }
        user.append("badcase 阈值：").append(dim.getBadcaseThreshold() == null ? "" : dim.getBadcaseThreshold()).append("\n\n");
        user.append("当前评分标准：\n");
        if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
            for (PromptGenerator.RubricItem r : dim.getRubric()) {
                user.append("  { \"level\": \"").append(r.getLevel()).append("\", \"desc\": \"")
                        .append(r.getDesc() == null ? "" : r.getDesc()).append("\" }\n");
            }
        }
        user.append("\n请润色每个 level 的 desc，保持 level 不变，输出如下格式JSON：\n");
        user.append("{\"rubric\": [ {\"level\": \"5\", \"desc\": \"优化后的描述\"}, ... ]}");

        String aiResponse = llmApiClient.chat(model, sys.toString(), user.toString());

        // 解析返回的 rubric
        String jsonStr = aiResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }

        JSONObject resultDim = buildDimBase(dim);

        try {
            JSONObject resp = JSONUtil.parseObj(jsonStr);
            JSONArray rubric = resp.getJSONArray("rubric");
            if (rubric != null && !rubric.isEmpty()) {
                resultDim.set("rubric", rubric);
            } else {
                // 失败则保留原 rubric
                resultDim.set("rubric", toRubricArray(dim));
            }
        } catch (Exception e) {
            log.warn("润色解析失败，保留原描述: dim={}", dim.getName(), e);
            resultDim.set("rubric", toRubricArray(dim));
        }

        return resultDim;
    }

    private JSONArray toRubricArray(PromptGenerator.DimensionDef dim) {
        JSONArray arr = new JSONArray();
        if (dim.getRubric() != null) {
            for (PromptGenerator.RubricItem r : dim.getRubric()) {
                JSONObject o = new JSONObject();
                o.set("level", r.getLevel());
                o.set("desc", r.getDesc());
                arr.add(o);
            }
        }
        return arr;
    }

    @Override
    public String parseToDimensions(Long modelId, String text) {
        if (StrUtil.isBlank(text)) {
            throw new BusinessException("请先描述你的评测标准");
        }

        // 获取识别模型
        EvalModelConfig model = modelId != null ? modelConfigMapper.selectById(modelId) : null;
        if (model == null || model.getStatus() != 1) {
            throw new BusinessException("模型不可用，请先在模型管理中配置并启用");
        }

        String sys = "你是一名专业的 AI 评测标准设计专家。用户会用自然语言描述对 AI 产品回答的评测要求，" +
                "你需要将其转换为结构化的评测维度配置。\n\n" +
                "输出必须是严格的JSON格式，结构如下：\n" +
                "{\n" +
                "  \"role\": \"你是一名专业的评测专家，负责评判AI回答的质量。\",\n" +
                "  \"context_template\": \"${question}：用户问题\\n${model_response}：模型回答\",\n" +
                "  \"badcase_rule\": \"any\",\n" +
                "  \"extra_instructions\": \"用户的补充要求(如有，否则为空字符串)\",\n" +
                "  \"dimensions\": [\n" +
                "    {\n" +
                "      \"name\": \"维度名称\",\n" +
                "      \"scoring_type\": \"score\",\n" +
                "      \"badcase_threshold\": \"<3\",\n" +
                "      \"rubric\": [\n" +
                "        { \"level\": \"5\", \"desc\": \"5分标准描述\" },\n" +
                "        { \"level\": \"3\", \"desc\": \"3分标准描述\" },\n" +
                "        { \"level\": \"1\", \"desc\": \"1分标准描述\" }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n\n" +
                "要求：\n" +
                "1. scoring_type 使用 \"score\"（1/3/5分制）或 \"boolean\"（采纳/不采纳）或 \"enum\"（分级，需提供 enum_values 数组）\n" +
                "2. 从用户描述中提取 2-5 个核心评测维度\n" +
                "3. 每个维度的 rubric 描述要具体、可衡量、可操作\n" +
                "4. context_template 必须填写，格式为 ${字段名}：说明，每行一个。常见占位符：${question}（用户问题）、${model_response}（模型回答）、${reference_answer}（参考答案，仅参考对照模式）、${context}（上下文）、${category}（分类）。根据用户描述的评测场景选择需要的占位符\n" +
                "5. role 必须以「你是」开头，描述裁判模型的身份定位，如「你是一名专业的XX评测专家」。不要照搬用户的原话，而是基于用户描述的场景提炼出裁判角色\n" +
                "6. 直接输出JSON，不要输出多余内容";

        String aiResponse = llmApiClient.chat(model, sys, text);

        // 提取 JSON
        String jsonStr = aiResponse.trim();
        if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
        }
        // 去掉可能的前缀文字
        int start = jsonStr.indexOf('{');
        int end = jsonStr.lastIndexOf('}');
        if (start >= 0 && end > start) {
            jsonStr = jsonStr.substring(start, end + 1);
        }

        try {
            // 验证 JSON 结构有效
            JSONObject parsed = JSONUtil.parseObj(jsonStr);
            JSONArray dims = parsed.getJSONArray("dimensions");
            if (dims == null || dims.isEmpty()) {
                throw new BusinessException("未能从描述中识别出评测维度，请重新描述或手动编辑");
            }
            // 确保每个维度有完整字段
            for (int i = 0; i < dims.size(); i++) {
                JSONObject d = dims.getJSONObject(i);
                if (!d.containsKey("name") || StrUtil.isBlank(d.getStr("name"))) {
                    throw new BusinessException("识别出的维度缺少名称");
                }
                if (!d.containsKey("scoring_type")) d.set("scoring_type", "score");
                if (!d.containsKey("badcase_threshold")) d.set("badcase_threshold", "<3");
                if (!d.containsKey("rubric") || d.getJSONArray("rubric") == null || d.getJSONArray("rubric").isEmpty()) {
                    JSONArray defRubric = new JSONArray();
                    JSONObject r5 = new JSONObject(); r5.set("level", "5"); r5.set("desc", "优秀");
                    JSONObject r3 = new JSONObject(); r3.set("level", "3"); r3.set("desc", "合格");
                    JSONObject r1 = new JSONObject(); r1.set("level", "1"); r1.set("desc", "不合格");
                    defRubric.add(r5); defRubric.add(r3); defRubric.add(r1);
                    d.set("rubric", defRubric);
                }
            }
            // 规范化角色设定：去句号、截断过长内容
            String role = parsed.getStr("role");
            if (StrUtil.isNotBlank(role)) {
                role = role.replaceAll("[。.]+$", "").trim(); // 去掉末尾句号
                if (role.length() > 80) role = role.substring(0, 80); // 截断过长的描述
            }
            if (StrUtil.isBlank(role)) {
                role = "你是一名专业的 AI 回答质量评测专家";
            }
            parsed.set("role", role);
            // 兜底：如果 AI 没有生成 context_template，自动填充默认值
            if (!parsed.containsKey("context_template") || StrUtil.isBlank(parsed.getStr("context_template"))) {
                parsed.set("context_template", "${question}：用户问题\n${model_response}：模型回答");
            }
            return parsed.toString();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("解析自然语言评测标准失败: {}", aiResponse, e);
            throw new BusinessException("AI 识别结果解析失败，请重试或手动编辑");
        }
    }
}
