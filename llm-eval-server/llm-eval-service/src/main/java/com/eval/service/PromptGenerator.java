package com.eval.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    public static class DimensionResult {
        private String name;
        private Object score; // Integer / String / Boolean
        private String reason;
        private boolean isBadcase;
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

        // 6. 输出格式（根据维度定义自动生成）
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
                sb.append("\"result\": true/false, \"reason\": \"简要说明\"");
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
        sb.append("  \"is_badcase\": true/false,\n");
        sb.append("  \"reason\": \"整体判定理由\"\n}\n```\n");

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
            sb.append("true/false");
        }
        sb.append(",\n");
        sb.append("  \"reason\": \"简要说明\"\n}\n```\n");

        return sb.toString();
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
                dr.setBadcase(evaluateThreshold(score, dim.getBadcaseThreshold()));
            } else if ("enum".equals(dim.getScoringType()) || "custom".equals(dim.getScoringType())) {
                String result = jsonObj.getStr("result", jsonObj.getStr("score", ""));
                dr.setScore(result);
                dr.setReason(jsonObj.getStr("reason", ""));
                dr.setBadcase(evaluateEnumThreshold(result, dim.getBadcaseThreshold()));
            } else if ("boolean".equals(dim.getScoringType())) {
                Boolean val = jsonObj.containsKey("result") ? jsonObj.getBool("result", false) : jsonObj.getBool("score", false);
                dr.setScore(val);
                dr.setReason(jsonObj.getStr("reason", ""));
                dr.setBadcase(evaluateBooleanThreshold(val, dim.getBadcaseThreshold()));
            }

            dr.setReason(jsonObj.getStr("reason", dr.getReason() != null ? dr.getReason().toString() : ""));
        } catch (Exception e) {
            log.warn("解析单维度评测结果失败: {}", aiResponse, e);
            dr.setBadcase(false);
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
                            dr.setBadcase(evaluateThreshold(dimResult.getInt("score", 0), dimDef.getBadcaseThreshold()));
                        } else if ("enum".equals(dimDef.getScoringType()) || "custom".equals(dimDef.getScoringType())) {
                            dr.setScore(dimResult.getStr("result", ""));
                            dr.setReason(dimResult.getStr("reason", ""));
                            dr.setBadcase(evaluateEnumThreshold(dimResult.getStr("result", ""), dimDef.getBadcaseThreshold()));
                        } else if ("boolean".equals(dimDef.getScoringType())) {
                            Boolean val = dimResult.getBool("result", false);
                            dr.setScore(val);
                            dr.setReason(dimResult.getStr("reason", ""));
                            dr.setBadcase(evaluateBooleanThreshold(val, dimDef.getBadcaseThreshold()));
                        }
                    }

                    result.getDimensionResults().add(dr);
                    if (dr.isBadcase()) {
                        result.getBadDimensions().add(dimDef.getName());
                    }
                }
            }

            // 整体 is_badcase：优先取 AI 返回的值，没有则根据规则计算
            if (jsonObj.containsKey("is_badcase")) {
                result.setBadcase(jsonObj.getBool("is_badcase", false));
            } else if (jsonObj.containsKey("badcase")) {
                Object val = jsonObj.get("badcase");
                if (val instanceof Boolean) result.setBadcase((Boolean) val);
                else if (val != null) {
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
        private boolean badcase;
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
