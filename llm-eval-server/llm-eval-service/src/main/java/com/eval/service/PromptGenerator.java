package com.eval.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评测Prompt结构化生成与解析
 * 根据 dimensions_config 自动生成 Prompt 模板，并解析 AI 返回结果
 */
@Slf4j
@Component
public class PromptGenerator {

    // ========== 数据结构 ==========

    @Data
    public static class DimensionsConfig {
        private String role;
        private String contextTemplate;
        private List<DimensionDef> dimensions;
        private String badcaseRule; // any / all / majority
        private String extraInstructions;
        /** 严格输出开关：开启时在 Prompt 末尾注入输出格式规范(JSON Schema)，并要求模型严格遵循 */
        private Boolean strictOutput;
        /** 思维链引导：开启时要求模型逐步分析后再输出评分 */
        private Boolean enableCot;
        /** Few-shot 评测示例 */
        private List<FewShotExample> fewShots;

        public static DimensionsConfig fromJson(String json) {
            if (StrUtil.isBlank(json)) return null;
            try {
                // 修复：MySQL TEXT中可能存储了裸换行符，需替换为\\n才能被JSON解析
                String sanitized = json.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
                JSONObject obj = JSONUtil.parseObj(sanitized);
                DimensionsConfig config = new DimensionsConfig();
                config.setRole(obj.getStr("role", ""));
                config.setContextTemplate(obj.getStr("context_template", ""));
                config.setBadcaseRule(obj.getStr("badcase_rule", "any"));
                config.setExtraInstructions(obj.getStr("extra_instructions", ""));
                // strict_output：兼容旧数据（缺失默认 false）
                config.setStrictOutput(obj.getBool("strict_output", false));
                config.setEnableCot(obj.getBool("enable_cot", false));

                // few_shots
                JSONArray fsArr = obj.getJSONArray("few_shots");
                if (fsArr != null) {
                    List<FewShotExample> fsList = new ArrayList<>();
                    for (int i = 0; i < fsArr.size(); i++) {
                        JSONObject fs = fsArr.getJSONObject(i);
                        FewShotExample ex = new FewShotExample();
                        ex.setQuestion(fs.getStr("question", ""));
                        ex.setResponse(fs.getStr("response", ""));
                        ex.setReference(fs.getStr("reference", ""));
                        ex.setExpectedOutput(fs.getStr("expected_output", ""));
                        fsList.add(ex);
                    }
                    config.setFewShots(fsList);
                }

                JSONArray dims = obj.getJSONArray("dimensions");
                if (dims != null) {
                    List<DimensionDef> list = new ArrayList<>();
                    for (int i = 0; i < dims.size(); i++) {
                        JSONObject d = dims.getJSONObject(i);
                        DimensionDef def = new DimensionDef();
                        def.setName(d.getStr("name", ""));
                        def.setScoringType(d.getStr("scoring_type", "score"));
                        def.setBadcaseThreshold(d.getStr("badcase_threshold", ""));
                        // rubric
                        JSONArray rubric = d.getJSONArray("rubric");
                        if (rubric != null) {
                            List<RubricItem> rubricList = new ArrayList<>();
                            for (int j = 0; j < rubric.size(); j++) {
                                JSONObject r = rubric.getJSONObject(j);
                                RubricItem item = new RubricItem();
                                item.setLevel(r.getStr("level", ""));
                                item.setDesc(r.getStr("desc", ""));
                                rubricList.add(item);
                            }
                            def.setRubric(rubricList);
                        }
                        // enum_values (scoring_type=enum时)
                        JSONArray enumVals = d.getJSONArray("enum_values");
                        if (enumVals != null) {
                            List<String> vals = new ArrayList<>();
                            for (int k = 0; k < enumVals.size(); k++) {
                                vals.add(enumVals.getStr(k));
                            }
                            def.setEnumValues(vals.toArray(new String[0]));
                        }
                        list.add(def);
                    }
                    config.setDimensions(list);
                }
                return config;
            } catch (Exception e) {
                log.warn("解析dimensions_config失败: {}", e.getMessage());
                return null;
            }
        }

        public String toJson() {
            return JSONUtil.toJsonStr(this);
        }
    }

    @Data
    public static class DimensionDef {
        private String name;
        private String scoringType; // score / enum / boolean
        private List<RubricItem> rubric;
        private String badcaseThreshold; // <80 / =B / =否 等
        private String[] enumValues; // enum 类型的可选值
    }

    @Data
    public static class RubricItem {
        private String level;
        private String desc;
    }

    @Data
    public static class FewShotExample {
        private String question;
        private String response;
        private String reference;
        private String expectedOutput;
    }

    @Data
    public static class DimensionResult {
        private String name;
        private Object score; // Integer / String / Boolean / null(unknown)
        private String reason;
        /** 是否判定为 badcase；null=unknown（AI 无法判断该维度） */
        private Boolean isBadcase;
    }

    // ========== Prompt 生成 ==========

    /**
     * 根据 dimensions_config 自动生成完整的评测 Prompt
     */
    public String generatePrompt(DimensionsConfig config) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定
        if (StrUtil.isNotBlank(config.getRole())) {
            sb.append(config.getRole()).append("\n\n");
        }

        // 2. 数据注入模板
        if (StrUtil.isNotBlank(config.getContextTemplate())) {
            sb.append("【评测数据】\n").append(config.getContextTemplate()).append("\n\n");
        }

        // 3. 评分维度与标准
        sb.append("【评分维度与标准】\n");
        for (int i = 0; i < config.getDimensions().size(); i++) {
            DimensionDef dim = config.getDimensions().get(i);
            sb.append(i + 1).append(". ").append(dim.getName());
            if ("score".equals(dim.getScoringType())) {
                sb.append("（1-5分，分数越低越差）");
            } else if ("enum".equals(dim.getScoringType()) && dim.getEnumValues() != null) {
                sb.append("（可选值：").append(String.join("/", dim.getEnumValues())).append("）");
            } else if ("boolean".equals(dim.getScoringType())) {
                sb.append("（是/否）");
            } else {
                // custom：用 rubric 的 level 作为可选等级
                String levels = "";
                if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                    levels = dim.getRubric().stream()
                            .map(RubricItem::getLevel)
                            .filter(l -> l != null && !l.isEmpty())
                            .collect(Collectors.joining("/"));
                }
                if (!levels.isEmpty()) sb.append("（等级：").append(levels).append("）");
            }
            sb.append("\n");

            if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                for (RubricItem r : dim.getRubric()) {
                    sb.append("   - ").append(r.getLevel());
                    if ("score".equals(dim.getScoringType())) sb.append("分");
                    sb.append("：").append(r.getDesc()).append("\n");
                }
            }
            sb.append("\n");
        }

        // 4. 额外指令
        if (StrUtil.isNotBlank(config.getExtraInstructions())) {
            sb.append(config.getExtraInstructions()).append("\n\n");
        }

        // 5. badcase 判定规则
        sb.append("【badcase判定规则】\n");
        switch (config.getBadcaseRule()) {
            case "any":
                sb.append("任一维度达badcase阈值，则整体判定为badcase。\n\n");
                break;
            case "all":
                sb.append("所有维度均达badcase阈值，才判定为badcase。\n\n");
                break;
            case "majority":
                sb.append("超过半数维度达badcase阈值，则整体判定为badcase。\n\n");
                break;
            default:
                sb.append("任一维度达badcase阈值，则整体判定为badcase。\n\n");
        }
        // 每个维度的阈值说明
        for (DimensionDef dim : config.getDimensions()) {
            if (StrUtil.isNotBlank(dim.getBadcaseThreshold())) {
                sb.append("- ").append(dim.getName()).append(" badcase条件：").append(dim.getBadcaseThreshold()).append("\n");
            }
        }
        sb.append("\n");

        // 5.5 评测示例（few-shot）
        if (config.getFewShots() != null && !config.getFewShots().isEmpty()) {
            sb.append("【评测示例】\n");
            for (int i = 0; i < config.getFewShots().size(); i++) {
                FewShotExample ex = config.getFewShots().get(i);
                sb.append("示例").append(i + 1).append("：\n");
                if (StrUtil.isNotBlank(ex.getQuestion())) sb.append("问题：").append(ex.getQuestion()).append("\n");
                if (StrUtil.isNotBlank(ex.getReference())) sb.append("参考答案：").append(ex.getReference()).append("\n");
                if (StrUtil.isNotBlank(ex.getResponse())) sb.append("模型回答：").append(ex.getResponse()).append("\n");
                if (StrUtil.isNotBlank(ex.getExpectedOutput())) sb.append("期望输出：\n").append(ex.getExpectedOutput()).append("\n");
                sb.append("\n");
            }
        }

        // 6. 输出格式（根据维度定义自动生成）
        // 开启 strict_output 时只输出严格 schema（见下方 buildOutputSchemaBlock），避免与示例重复
        if (!Boolean.TRUE.equals(config.getStrictOutput())) {
            sb.append("【输出格式】\n严格按照以下JSON格式输出，不得输出多余内容：\n\n```json\n{\n");
            sb.append("  \"dimensions\": {\n");
            for (int i = 0; i < config.getDimensions().size(); i++) {
                DimensionDef dim = config.getDimensions().get(i);
                sb.append("    \"").append(dim.getName()).append("\": { ");
                if ("score".equals(dim.getScoringType())) {
                    sb.append("\"score\": 1-5, \"reason\": \"简要说明\"");
                } else if ("enum".equals(dim.getScoringType())) {
                    sb.append("\"result\": \"").append(String.join("/", dim.getEnumValues())).append("\", \"reason\": \"简要说明\"");
                } else if ("boolean".equals(dim.getScoringType())) {
                    sb.append("\"result\": true/false（无法判断时填 null）, \"reason\": \"简要说明\"");
                } else {
                    // custom：用 rubric 的 level 作为可选等级
                    String levels = "";
                    if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                        levels = dim.getRubric().stream()
                                .map(RubricItem::getLevel)
                                .filter(l -> l != null && !l.isEmpty())
                                .collect(Collectors.joining("/"));
                    }
                    if (levels.isEmpty()) levels = "自定义等级";
                    sb.append("\"result\": \"").append(levels).append("\", \"reason\": \"简要说明\"");
                }
                sb.append(" }");
                if (i < config.getDimensions().size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  },\n");
            sb.append("  \"is_badcase\": true/false（无法判断时填 null）,\n");
            sb.append("  \"reason\": \"整体判定理由\"\n}\n```\n");
        }

        // enable_cot：注入思维链引导指令
        if (Boolean.TRUE.equals(config.getEnableCot())) {
            sb.append("\n【思维链引导】\n");
            sb.append("在输出最终JSON之前，请先逐步分析：\n");
            sb.append("1. 逐个分析各评分维度，给出每个维度的判断依据\n");
            sb.append("2. 综合各维度分析，给出整体判定理由\n");
            sb.append("3. 最后输出符合格式要求的JSON\n\n");
        }

        // strict_output：注入输出格式规范，要求模型严格遵循
        if (Boolean.TRUE.equals(config.getStrictOutput())) {
            sb.append("\n").append(buildOutputSchemaBlock(config, null));
        }

        return sb.toString();
    }

    // ========== 单维度 Prompt 生成与解析 ==========

    /**
     * 生成单个维度的评测 Prompt（一次只评测一个维度）
     * 每个维度独立调用裁判模型，避免多维度互相干扰（晕轮效应）
     */
    public String generateDimensionPrompt(DimensionsConfig config, DimensionDef dim) {
        StringBuilder sb = new StringBuilder();

        // 1. 角色设定
        if (StrUtil.isNotBlank(config.getRole())) {
            sb.append(config.getRole()).append("\n\n");
        }

        // 2. 数据注入模板
        if (StrUtil.isNotBlank(config.getContextTemplate())) {
            sb.append("【评测数据】\n").append(config.getContextTemplate()).append("\n\n");
        }

        // 3. 评测维度（单维度）
        sb.append("【本次评测维度】\n");
        sb.append("1. ").append(dim.getName());
        if ("score".equals(dim.getScoringType())) {
            sb.append("（1-5分，分数越低越差）");
        } else if ("enum".equals(dim.getScoringType()) && dim.getEnumValues() != null) {
            sb.append("（可选值：").append(String.join("/", dim.getEnumValues())).append("）");
        } else if ("boolean".equals(dim.getScoringType())) {
            sb.append("（是/否，若资料不足无法判断请输出 null）");
        } else {
            // custom：用 rubric 的 level 作为可选等级
            String levels = "";
            if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                levels = dim.getRubric().stream()
                        .map(RubricItem::getLevel)
                        .filter(l -> l != null && !l.isEmpty())
                        .collect(Collectors.joining("/"));
            }
            if (!levels.isEmpty()) sb.append("（等级：").append(levels).append("）");
        }
        sb.append("\n");

        // Rubric 标准
        if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
            for (RubricItem r : dim.getRubric()) {
                sb.append("   - ").append(r.getLevel());
                if ("score".equals(dim.getScoringType())) sb.append("分");
                sb.append("：").append(r.getDesc()).append("\n");
            }
        }
        sb.append("\n");

        // 4. 额外指令
        if (StrUtil.isNotBlank(config.getExtraInstructions())) {
            sb.append(config.getExtraInstructions()).append("\n\n");
        }

        // 5. 输出格式（单维度）
        // 开启 strict_output 时只输出严格 schema，避免与示例重复
        if (!Boolean.TRUE.equals(config.getStrictOutput())) {
            sb.append("【输出格式】\n严格按照以下JSON格式输出，不得输出多余内容：\n\n```json\n{\n");
            sb.append("  \"score\": ");
            if ("score".equals(dim.getScoringType())) {
                sb.append("1-5");
            } else if ("enum".equals(dim.getScoringType()) || "custom".equals(dim.getScoringType())) {
            String levels = "";
            if (dim.getEnumValues() != null && dim.getEnumValues().length > 0) {
                levels = String.join("/", dim.getEnumValues());
            } else if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                levels = dim.getRubric().stream()
                        .map(RubricItem::getLevel)
                        .filter(l -> l != null && !l.isEmpty())
                        .collect(Collectors.joining("/"));
            }
            if (levels.isEmpty()) levels = "等级";
            sb.append("\"").append(levels).append("\"");
        } else if ("boolean".equals(dim.getScoringType())) {
            sb.append("true/false（无法判断时填 null）");
        }
        sb.append(",\n");
        sb.append("  \"reason\": \"简要说明\"\n}\n```\n");
        }

        // enable_cot：注入思维链引导指令
        if (Boolean.TRUE.equals(config.getEnableCot())) {
            sb.append("\n【思维链引导】\n");
            sb.append("在输出最终JSON之前，请先分析：\n");
            sb.append("1. 根据评分标准，分析模型回答在该维度的表现\n");
            sb.append("2. 给出判断依据\n");
            sb.append("3. 最后输出符合格式要求的JSON\n\n");
        }

        // strict_output：注入单维度输出格式规范
        if (Boolean.TRUE.equals(config.getStrictOutput())) {
            sb.append("\n").append(buildOutputSchemaBlock(config, dim));
        }

        return sb.toString();
    }

    // ========== 输出格式规范（strict_output） ==========

    /**
     * 生成输出格式规范（JSON Schema 风格），strict_output 开启时注入 Prompt
     * @param config 完整维度配置
     * @param dimension null=生成整体规范（is_badcase + dimensions）；非null=仅生成单维度规范
     */
    private String buildOutputSchemaBlock(DimensionsConfig config, DimensionDef dimension) {
        StringBuilder sb = new StringBuilder();
        sb.append("【输出格式规范】\n必须严格按照以下 schema 输出 JSON 对象，key 完全一致，值必须为合法类型，不得输出 schema 之外的任何 key：\n\n");
        if (dimension != null) {
            sb.append("{\n");
            appendDimFieldSchema(sb, dimension, 1);
            sb.append("  \"reason\": \"字符串，判定理由\"\n");
            sb.append("}\n");
        } else {
            sb.append("{\n");
            sb.append("  \"is_badcase\": 布尔值 true/false 或 null（是否整体判定为 badcase；资料不足无法判断时填 null）,\n");
            sb.append("  \"dimensions\": {\n");
            if (config.getDimensions() != null) {
                for (int i = 0; i < config.getDimensions().size(); i++) {
                    DimensionDef dim = config.getDimensions().get(i);
                    sb.append("    \"").append(dim.getName()).append("\": {\n");
                    appendDimFieldSchema(sb, dim, 3);
                    sb.append("      \"reason\": \"字符串\"\n");
                    sb.append("    }");
                    if (i < config.getDimensions().size() - 1) sb.append(",");
                    sb.append("\n");
                }
            }
            sb.append("  },\n");
            sb.append("  \"reason\": \"字符串，整体判定理由\"\n");
            sb.append("}\n");
        }
        sb.append("请直接输出 JSON（不要用 markdown 代码块包裹，不要输出任何多余文字）。");
        return sb.toString();
    }

    /**
     * 追加单个维度结果字段的 schema 描述
     */
    private void appendDimFieldSchema(StringBuilder sb, DimensionDef dim, int indent) {
        String pad = "";
        for (int i = 0; i < indent; i++) pad += "  ";
        if ("score".equals(dim.getScoringType())) {
            String range = "1-5 的整数";
            if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                List<String> levels = dim.getRubric().stream()
                        .map(RubricItem::getLevel)
                        .filter(l -> l != null && !l.isEmpty())
                        .collect(Collectors.toList());
                if (!levels.isEmpty()) range = "整数，只能取值为 " + String.join("、", levels);
            }
            sb.append(pad).append("\"score\": ").append(range).append(",\n");
        } else if ("enum".equals(dim.getScoringType())) {
            String allowed = dim.getEnumValues() != null && dim.getEnumValues().length > 0
                    ? String.join("/", dim.getEnumValues()) : "枚举值";
            sb.append(pad).append("\"result\": 字符串，只能是以下值之一: ").append(allowed).append(",\n");
        } else if ("boolean".equals(dim.getScoringType())) {
            sb.append(pad).append("\"result\": 布尔值 true/false 或 null（无法判断时填 null）,\n");
        } else {
            // custom：优先 enum_values，其次 rubric level
            String allowed = "";
            if (dim.getEnumValues() != null && dim.getEnumValues().length > 0) {
                allowed = String.join("/", dim.getEnumValues());
            } else if (dim.getRubric() != null && !dim.getRubric().isEmpty()) {
                allowed = dim.getRubric().stream()
                        .map(RubricItem::getLevel)
                        .filter(l -> l != null && !l.isEmpty())
                        .collect(Collectors.joining("/"));
            }
            if (allowed.isEmpty()) allowed = "枚举值";
            sb.append(pad).append("\"result\": 字符串，只能是以下值之一: ").append(allowed).append(",\n");
        }
    }

    /**
     * 解析单维度评测结果
     */
    public DimensionResult parseDimensionResponse(String aiResponse, DimensionDef dim) {
        DimensionResult dr = new DimensionResult();
        dr.setName(dim.getName());

        try {
            String jsonStr = aiResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JSONObject jsonObj = JSONUtil.parseObj(jsonStr);

            if ("score".equals(dim.getScoringType())) {
                int score = jsonObj.getInt("score", 0);
                dr.setScore(score);
                dr.setReason(jsonObj.getStr("reason", ""));
                dr.setIsBadcase(evaluateThreshold(score, dim.getBadcaseThreshold()));
            } else if ("enum".equals(dim.getScoringType()) || "custom".equals(dim.getScoringType())) {
                Object rawResult = jsonObj.containsKey("result") ? jsonObj.get("result") : jsonObj.get("score");
                String enumVal = rawResult != null ? rawResult.toString() : "";
                dr.setScore(enumVal);
                dr.setReason(jsonObj.getStr("reason", ""));
                // 校验枚举值是否在 schema 允许范围内（strict_output 模式更易触发）；unknown 值豁免
                if (dim.getEnumValues() != null && dim.getEnumValues().length > 0 && !isUnknownValue(rawResult)) {
                    boolean allowed = Arrays.asList(dim.getEnumValues()).contains(enumVal);
                    if (!allowed) {
                        log.warn("维度[{}]枚举值不在schema允许范围内: value={}, allowed={}",
                                dim.getName(), enumVal, String.join("/", dim.getEnumValues()));
                    }
                }
                // AI 表明无法判断 → unknown；否则按阈值判断
                if (isUnknownValue(rawResult)) {
                    dr.setIsBadcase(null);
                } else {
                    dr.setIsBadcase(evaluateEnumThreshold(enumVal, dim.getBadcaseThreshold()));
                }
            } else if ("boolean".equals(dim.getScoringType())) {
                Object rawVal = jsonObj.containsKey("result") ? jsonObj.get("result") : jsonObj.get("score");
                Boolean val = isUnknownValue(rawVal) ? null : parseBooleanValue(rawVal);
                dr.setScore(val);
                dr.setReason(jsonObj.getStr("reason", ""));
                dr.setIsBadcase(val == null ? null : evaluateBooleanThreshold(val, dim.getBadcaseThreshold()));
            }

            dr.setReason(jsonObj.getStr("reason", dr.getReason() != null ? dr.getReason().toString() : ""));
        } catch (Exception e) {
            log.warn("解析单维度评测结果失败: {}", aiResponse, e);
            dr.setIsBadcase(false);
            dr.setReason("解析AI结果失败: " + aiResponse);
        }

        return dr;
    }

    // ========== 结果解析 ==========

    /**
     * 根据 dimensions_config 解析 AI 返回的 JSON
     * 返回各维度的评分结果 + 整体 is_badcase
     */
    public ParseResult parseAiResponse(String aiResponse, DimensionsConfig config) {
        ParseResult result = new ParseResult();
        result.setDimensionResults(new ArrayList<>());
        result.setBadDimensions(new ArrayList<>());

        try {
            String jsonStr = aiResponse.trim();
            if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
            }

            JSONObject jsonObj = JSONUtil.parseObj(jsonStr);

            // 从 dimensions 对象中提取各维度结果
            JSONObject dimsObj = jsonObj.getJSONObject("dimensions");
            if (dimsObj != null && config != null && config.getDimensions() != null) {
                for (DimensionDef dimDef : config.getDimensions()) {
                    DimensionResult dr = new DimensionResult();
                    dr.setName(dimDef.getName());

                    JSONObject dimResult = dimsObj.getJSONObject(dimDef.getName());
                    if (dimResult != null) {
                        if ("score".equals(dimDef.getScoringType())) {
                            dr.setScore(dimResult.getInt("score", 0));
                            dr.setReason(dimResult.getStr("reason", ""));
                            dr.setIsBadcase(evaluateThreshold(dimResult.getInt("score", 0), dimDef.getBadcaseThreshold()));
                        } else if ("enum".equals(dimDef.getScoringType()) || "custom".equals(dimDef.getScoringType())) {
                            Object rawResult = dimResult.containsKey("result") ? dimResult.get("result") : dimResult.get("score");
                            String enumVal = rawResult != null ? rawResult.toString() : "";
                            dr.setScore(enumVal);
                            dr.setReason(dimResult.getStr("reason", ""));
                            if (isUnknownValue(rawResult)) {
                                dr.setIsBadcase(null);
                            } else {
                                dr.setIsBadcase(evaluateEnumThreshold(enumVal, dimDef.getBadcaseThreshold()));
                            }
                        } else if ("boolean".equals(dimDef.getScoringType())) {
                            Object rawVal = dimResult.containsKey("result") ? dimResult.get("result") : dimResult.get("score");
                            Boolean val = isUnknownValue(rawVal) ? null : parseBooleanValue(rawVal);
                            dr.setScore(val);
                            dr.setReason(dimResult.getStr("reason", ""));
                            dr.setIsBadcase(val == null ? null : evaluateBooleanThreshold(val, dimDef.getBadcaseThreshold()));
                        }
                    }

                    result.getDimensionResults().add(dr);
                    if (Boolean.TRUE.equals(dr.getIsBadcase())) {
                        result.getBadDimensions().add(dimDef.getName());
                    }
                }
            }

            // 整体 is_badcase：优先取 AI 返回的值（支持 null=unknown），没有则根据规则计算
            if (jsonObj.containsKey("is_badcase")) {
                Object raw = jsonObj.get("is_badcase");
                result.setBadcase(isUnknownValue(raw) ? null : parseBooleanValue(raw));
            } else if (jsonObj.containsKey("badcase")) {
                Object val = jsonObj.get("badcase");
                if (isUnknownValue(val)) {
                    result.setBadcase(null);
                } else if (val instanceof Boolean) {
                    result.setBadcase((Boolean) val);
                } else {
                    String s = val.toString().trim();
                    result.setBadcase("是".equals(s) || "true".equalsIgnoreCase(s) || "1".equals(s));
                }
            } else {
                // 根据规则计算
                result.setBadcase(applyBadcaseRule(result.getBadDimensions().size(),
                        config != null ? config.getDimensions().size() : 0,
                        config != null ? config.getBadcaseRule() : "any"));
            }

            // 整体 reason
            result.setReason(jsonObj.getStr("reason", ""));

            // 保存原始解析结果
            result.setParsedJson(jsonStr);

        } catch (Exception e) {
            log.warn("解析AI评测结果失败: {}", aiResponse, e);
            result.setBadcase(false);
            result.setReason("解析AI结果失败: " + aiResponse);
        }

        return result;
    }

    @Data
    public static class ParseResult {
        /** 整体是否 badcase；null=unknown */
        private Boolean badcase;
        private List<String> badDimensions;
        private List<DimensionResult> dimensionResults;
        private String reason;
        private String parsedJson;
    }

    // ========== 阈值判定 ==========

    private boolean evaluateThreshold(int score, String threshold) {
        if (StrUtil.isBlank(threshold)) return false;
        threshold = threshold.trim();
        if (threshold.startsWith("<")) {
            return score < Integer.parseInt(threshold.substring(1).trim());
        } else if (threshold.startsWith("<=")) {
            return score <= Integer.parseInt(threshold.substring(2).trim());
        } else if (threshold.startsWith(">")) {
            return score > Integer.parseInt(threshold.substring(1).trim());
        } else if (threshold.startsWith(">=")) {
            return score >= Integer.parseInt(threshold.substring(2).trim());
        } else if (threshold.startsWith("=")) {
            return score == Integer.parseInt(threshold.substring(1).trim());
        }
        return false;
    }

    private boolean evaluateEnumThreshold(String value, String threshold) {
        if (StrUtil.isBlank(threshold) || StrUtil.isBlank(value)) return false;
        threshold = threshold.trim();
        if (threshold.startsWith("=")) {
            return threshold.substring(1).trim().equals(value.trim());
        }
        return threshold.equals(value.trim());
    }

    private boolean evaluateBooleanThreshold(Boolean value, String threshold) {
        if (StrUtil.isBlank(threshold)) return false;
        return "true".equalsIgnoreCase(threshold.trim()) == value;
    }

    /**
     * 判断 AI 输出是否为"unknown"表达（无法判断该维度/整体）
     * null、空串、unknown/unable/unclear、无法判断/不确定/未知 等 → true
     */
    private static boolean isUnknownValue(Object raw) {
        if (raw == null) return true;
        String s = raw.toString().trim();
        if (s.isEmpty()) return true;
        return "unknown".equalsIgnoreCase(s)
                || "unable".equalsIgnoreCase(s)
                || "unclear".equalsIgnoreCase(s)
                || "cannot".equalsIgnoreCase(s.replace(" ", ""))
                || "不确定".equals(s)
                || "无法判断".equals(s)
                || "无法确定".equals(s)
                || "无法判定".equals(s)
                || "无法评估".equals(s)
                || "资料不足".equals(s)
                || "信息不足".equals(s)
                || "未知".equals(s);
    }

    /**
     * 解析布尔值：Boolean 原样返回；数字 0/非0；字符串 true/是/1 → true，其余 → false
     * （调用方应先 isUnknownValue 判断，此处不再处理 unknown）
     */
    private static Boolean parseBooleanValue(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        String s = raw.toString().trim();
        return "true".equalsIgnoreCase(s) || "是".equals(s) || "1".equals(s);
    }

    public boolean applyBadcaseRule(int badDimCount, int totalDimCount, String rule) {
        if ("all".equals(rule)) {
            return badDimCount == totalDimCount && totalDimCount > 0;
        } else if ("majority".equals(rule)) {
            return badDimCount > totalDimCount / 2.0;
        } else {
            // any
            return badDimCount > 0;
        }
    }
}
