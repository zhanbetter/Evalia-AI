package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.dao.mapper.EvalModelConfigMapper;
import com.eval.dao.mapper.EvalPromptMapper;
import com.eval.dao.mapper.EvalPromptVersionMapper;
import com.eval.model.dto.PromptDTO;
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
    public EvalPrompt add(PromptDTO dto) {
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
    public void deleteById(Long id) {
        promptMapper.deleteById(id);
    }

    @Override
    public EvalPrompt getById(Long id) {
        return promptMapper.selectById(id);
    }

    @Override
    public String polish(Long modelId, String dimensionsConfig) {
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

        // 逐维度润色：每个维度单独调用模型，保持结构不变，只优化文案
        JSONObject resultConfig = new JSONObject();
        resultConfig.set("role", config.getRole());
        resultConfig.set("context_template", config.getContextTemplate());
        resultConfig.set("badcase_rule", config.getBadcaseRule());
        resultConfig.set("extra_instructions", config.getExtraInstructions());

        JSONArray resultDims = new JSONArray();
        for (PromptGenerator.DimensionDef dim : config.getDimensions()) {
            JSONObject polished = polishDimension(model, dim);
            resultDims.add(polished);
        }
        resultConfig.set("dimensions", resultDims);

        return resultConfig.toString();
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
                "  \"role\": \"裁判模型角色设定，一句话描述其身份\",\n" +
                "  \"context_template\": \"\",\n" +
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
                "4. 直接输出JSON，不要输出多余内容";

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
            if (!parsed.containsKey("role") || StrUtil.isBlank(parsed.getStr("role"))) {
                parsed.set("role", "你是一名专业的 AI 回答质量评测专家");
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
