package com.eval.service.harness;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.eval.model.entity.EvalDatasetItem;
import com.eval.model.entity.EvalDatasetSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据集适配器：把 EvalDatasetItem 归一化为统一的 EvalSample。
 * 集中处理：
 *  1. extra_fields 的 JSON 解析
 *  2. 常见回答字段的提取（model_response / response / answer / output ...）
 *  3. 字段映射(占位符→数据集字段名)解析
 *  4. 占位符模板渲染（支持 ${xxx} 和 {xxx} 两种格式）
 */
@Slf4j
@Component
public class DatasetAdapter {

    /**
     * 将数据集条目转换为统一评测样本
     * @param item 数据集条目
     * @param modelResponse 被测模型回答（可为 null，稍后由生成阶段填入）
     */
    public EvalSample toSample(EvalDatasetItem item, String modelResponse) {
        EvalSample sample = new EvalSample();
        sample.setItemId(item.getId());
        sample.setQuestion(item.getQuestion() != null ? item.getQuestion() : "");
        sample.setReferenceAnswer(item.getReferenceAnswer() != null ? item.getReferenceAnswer() : "");
        sample.setContext(item.getContext() != null ? item.getContext() : "");
        sample.setCategory(item.getCategory() != null ? item.getCategory() : "");
        sample.setModelResponse(modelResponse != null ? modelResponse : "");
        sample.setExtraFields(parseExtraFields(item.getExtraFields()));
        return sample;
    }

    /**
     * 解析扩展字段 JSON 为 Map
     */
    public Map<String, String> parseExtraFields(String extraFieldsJson) {
        Map<String, String> map = new LinkedHashMap<>();
        if (StrUtil.isBlank(extraFieldsJson)) {
            return map;
        }
        try {
            JSONObject extra = JSONUtil.parseObj(extraFieldsJson);
            for (String key : extra.keySet()) {
                String val = extra.getStr(key);
                map.put(key, val != null ? val : "");
            }
        } catch (Exception ignored) {
            // 解析失败返回空 map，不抛异常
        }
        return map;
    }

    /**
     * 从数据集条目中提取已有回答（数据集预存回答场景）
     * 优先取 extra_fields 里的 model_response，其次尝试常见字段名
     */
    public String extractResponseFromItem(EvalDatasetItem item) {
        if (item == null) return null;
        Map<String, String> extra = parseExtraFields(item.getExtraFields());
        // 扩展后的常见模型回答字段名列表（中英文）
        for (String key : new String[]{
                "model_response", "response", "answer", "output",
                "模型回答", "回答", "模型回复", "生成结果", "模型输出",
                "prediction", "pred", "generated", "result", "回复"
        }) {
            if (extra.containsKey(key) && StrUtil.isNotBlank(extra.get(key))) {
                return extra.get(key);
            }
        }
        return null;
    }

    /**
     * 根据 schema 角色推断模型回答（优先 MODEL_RESPONSE，其次 name 匹配，最后 CUSTOM 推断）
     * @param item 数据集条目
     * @param schemaFields 数据集的 schema 字段定义
     */
    public String extractResponseFromItem(EvalDatasetItem item, List<com.eval.model.entity.EvalDatasetSchema> schemaFields) {
        // 先走原有逻辑（从 extraFields 中按名称匹配）
        String resp = extractResponseFromItem(item);
        if (resp != null) return resp;

        if (schemaFields == null || item == null) return null;
        Map<String, String> extra = parseExtraFields(item.getExtraFields());

        // 优先：显式标记为 MODEL_RESPONSE 的字段
        for (com.eval.model.entity.EvalDatasetSchema sf : schemaFields) {
            if ("MODEL_RESPONSE".equals(sf.getRole())) {
                String val = extra.get(sf.getFieldName());
                if (StrUtil.isNotBlank(val)) return val;
            }
        }

        // 其次：从 CUSTOM 字段中按名称推断
        for (com.eval.model.entity.EvalDatasetSchema sf : schemaFields) {
            if (!"CUSTOM".equals(sf.getRole())) continue;
            String fn = sf.getFieldName() != null ? sf.getFieldName().toLowerCase() : "";
            if (fn.contains("response") || fn.contains("answer") || fn.contains("输出")
                    || fn.contains("回答") || fn.contains("output") || fn.contains("pred")) {
                String val = extra.get(sf.getFieldName());
                if (StrUtil.isNotBlank(val)) return val;
            }
        }
        return null;
    }

    /**
     * 解析字段映射 JSON 为 Map<占位符key, 数据集字段名>
     * 只保留用户在创建任务时显式配置的映射；
     * 未被映射的占位符由 renderPrompt 在渲染时按占位符名直接匹配字段值兜底（兼容旧数据）。
     */
    public Map<String, String> parseFieldMapping(String fieldMappingJson) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(fieldMappingJson)) {
            try {
                JSONObject json = JSONUtil.parseObj(fieldMappingJson);
                for (String key : json.keySet()) {
                    String v = json.getStr(key);
                    if (StrUtil.isNotBlank(v)) {
                        mapping.put(key, v);
                    }
                }
            } catch (Exception e) {
                log.warn("解析fieldMapping失败，使用空映射: {}", e.getMessage());
            }
        }
        return mapping;
    }

    /**
     * 渲染 Prompt 模板：用字段映射 + 样本字段值替换占位符
     * 支持 ${xxx} 和 {xxx} 两种占位符格式。
     *
     * 替换优先级：
     *  1. 显式映射（fieldMapping）：{占位符} → 数据集字段名 对应的值
     *  2. 兜底：未映射的占位符，按占位符名直接匹配样本字段（question/model_response 等角色名或扩展字段名）
     *  3. 仍无法匹配的：替换为空字符串并记日志（避免把 {占位符} 原样发给模型）
     */
    public String renderPrompt(String template, EvalSample sample, Map<String, String> mapping) {
        if (StrUtil.isBlank(template)) {
            return template;
        }
        // 构建 "数据集字段名 → 字段值" 的全量映射
        Map<String, String> allValues = new LinkedHashMap<>();
        allValues.put("question", sample.getQuestion() != null ? sample.getQuestion() : "");
        allValues.put("reference_answer", sample.getReferenceAnswer() != null ? sample.getReferenceAnswer() : "");
        allValues.put("context", sample.getContext() != null ? sample.getContext() : "");
        allValues.put("category", sample.getCategory() != null ? sample.getCategory() : "");
        allValues.put("model_response", sample.getModelResponse() != null ? sample.getModelResponse() : "");
        if (sample.getExtraFields() != null) {
            allValues.putAll(sample.getExtraFields());
        }

        String result = template;
        Set<String> mapped = new HashSet<>();
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String placeholder = entry.getKey();
            String fieldName = entry.getValue();
            String value = allValues.getOrDefault(fieldName, "");
            result = result.replace("${" + placeholder + "}", value);
            result = result.replace("{" + placeholder + "}", value);
            mapped.add(placeholder);
        }

        // 兜底：未映射的占位符按名字直接匹配（mapped 集合避免误改映射值里天然包含的 {xxx} 文本）
        Matcher phMatcher = PLACEHOLDER_PATTERN.matcher(result);
        StringBuffer sb = new StringBuffer();
        while (phMatcher.find()) {
            String name = phMatcher.group(1) != null ? phMatcher.group(1) : phMatcher.group(2);
            if (mapped.contains(name)) {
                phMatcher.appendReplacement(sb, Matcher.quoteReplacement(phMatcher.group(0)));
                continue;
            }
            if (allValues.containsKey(name)) {
                phMatcher.appendReplacement(sb, Matcher.quoteReplacement(allValues.get(name)));
            } else {
                log.warn("渲染时发现未映射且无法匹配的占位符，已替换为空: {}", phMatcher.group(0));
                phMatcher.appendReplacement(sb, "");
            }
        }
        phMatcher.appendTail(sb);
        return sb.toString();
    }

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_:]*)\\}|\\{([a-zA-Z_][a-zA-Z0-9_:]*)\\}");
}
