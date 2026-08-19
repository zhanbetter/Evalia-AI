package com.eval.web.controller;

import cn.hutool.core.util.StrUtil;
import com.eval.common.result.Result;
import com.eval.model.entity.EvalModelConfig;
import com.eval.dao.mapper.EvalModelConfigMapper;
import com.eval.service.LlmApiClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playground：交互式 Prompt 调试
 */
@RestController
@RequestMapping("/api/playground")
@RequiredArgsConstructor
public class PlaygroundController {

    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;

    @PostMapping("/run")
    public Result<Map<String, Object>> run(@RequestBody PlaygroundRequest req) {
        // 1. 查模型配置
        EvalModelConfig model = modelConfigMapper.selectById(req.getModelConfigId());
        if (model == null) {
            return Result.fail("模型配置不存在");
        }

        // 2. 渲染 Prompt：用字段值替换占位符
        String prompt = req.getPromptTemplate();
        if (req.getFields() != null) {
            for (Map.Entry<String, String> entry : req.getFields().entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue() != null ? entry.getValue() : "";
                prompt = prompt.replace("${" + key + "}", val);
                prompt = prompt.replace("{" + key + "}", val);
            }
        }

        if (StrUtil.isBlank(prompt)) {
            return Result.fail("Prompt 不能为空");
        }

        // 3. 调用模型
        try {
            LlmApiClient.ChatResponse resp = llmApiClient.chatWithUsage(model, prompt);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("response", resp.getContent());
            result.put("prompt", prompt);
            result.put("latencyMs", resp.getLatencyMs());
            result.put("tokenUsage", resp.getTotalTokens());
            result.put("modelName", model.getName());
            return Result.success(result);
        } catch (Exception e) {
            return Result.fail("模型调用失败: " + e.getMessage());
        }
    }

    @Data
    public static class PlaygroundRequest {
        /** Prompt 模板（含 {xxx} 占位符） */
        private String promptTemplate;
        /** 模型配置 ID */
        private Long modelConfigId;
        /** 字段值 Map: { "question": "...", "context": "...", ... } */
        private Map<String, String> fields;
    }
}
