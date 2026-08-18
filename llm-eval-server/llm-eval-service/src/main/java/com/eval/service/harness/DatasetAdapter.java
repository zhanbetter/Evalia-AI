package com.eval.service.harness;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.eval.model.entity.EvalDatasetItem;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据集适配器：把 EvalDatasetItem 归一化为统一的 EvalSample。
 * 集中处理：
 *  1. extra_fields 的 JSON 解析
 *  2. 常见回答字段的提取（model_response / response / answer / output ...）
 *  3. 字段映射(占位符→数据集字段名)解析
 *  4. 占位符模板渲染（支持 ${xxx} 和 {xxx} 两种格式）
 */
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
     * 优先取 extra_fields 里的 model_response，其次尝试常见字段
     */
    public String extractResponseFromItem(EvalDatasetItem item) {
        if (item == null) return null;
        Map<String, String> extra = parseExtraFields(item.getExtraFields());
        for (String key : new String[]{"model_response", "response", "answer", "output", "模型回答", "回答"}) {
            if (extra.containsKey(key) && StrUtil.isNotBlank(extra.get(key))) {
                return extra.get(key);
            }
        }
        return null;
    }

    /**
     * 解析字段映射 JSON 为 Map<占位符key, 数据集字段名>
     * 缺省时补 question 默认映射
     */
    public Map<String, String> parseFieldMapping(String fieldMappingJson) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(fieldMappingJson)) {
            try {
                JSONObject json = JSONUtil.parseObj(fieldMappingJson);
                for (String key : json.keySet()) {
                    mapping.put(key, json.getStr(key));
                }
            } catch (Exception e) {
                // 解析失败使用默认映射
            }
        }
        if (!mapping.containsKey("question")) {
            mapping.put("question", "question");
        }
        if (!mapping.containsKey("model_response")) {
            mapping.put("model_response", "model_response");
        }
        return mapping;
    }

    /**
     * 渲染 Prompt 模板：用字段映射 + 样本字段值替换占位符
     * 支持 ${xxx} 和 {xxx} 两种占位符格式
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
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String placeholder = entry.getKey();
            String fieldName = entry.getValue();
            String value = allValues.getOrDefault(fieldName, "");
            result = result.replace("${" + placeholder + "}", value);
            result = result.replace("{" + placeholder + "}", value);
        }
        return result;
    }
}
