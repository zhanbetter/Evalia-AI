package com.eval.service.harness;

import cn.hutool.core.util.StrUtil;
import com.eval.model.entity.EvalModelConfig;
import com.eval.service.LlmApiClient;
import com.eval.service.PromptGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 裁判指标：用一个裁判模型对样本的"单个维度"进行判定。
 * 每个维度一次独立调用，避免多维度互相干扰（晕轮效应）。
 *
 * config 需要包含：
 *   - judgeModel       (EvalModelConfig)  裁判模型配置
 *   - dimensionsConfig (PromptGenerator.DimensionsConfig) 完整维度配置（提供 role/context/extra_instructions）
 *   - dimension        (PromptGenerator.DimensionDef)     本次要判定的单个维度
 *   - fieldMapping     (Map<String,String>) 占位符→字段名映射
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmJudgeMetric implements Metric {

    private final LlmApiClient llmApiClient;
    private final PromptGenerator promptGenerator;
    private final DatasetAdapter datasetAdapter;

    @Override
    public String name() {
        return "llm_judge";
    }

    @Override
    public MetricResult evaluate(EvalSample sample, Map<String, Object> config) {
        EvalModelConfig judgeModel = (EvalModelConfig) config.get("judgeModel");
        PromptGenerator.DimensionsConfig dimConfig = (PromptGenerator.DimensionsConfig) config.get("dimensionsConfig");
        PromptGenerator.DimensionDef dimension = (PromptGenerator.DimensionDef) config.get("dimension");
        @SuppressWarnings("unchecked")
        Map<String, String> fieldMapping = (Map<String, String>) config.get("fieldMapping");

        String dimName = dimension != null ? dimension.getName() : "未知维度";

        // 1. 生成单维度 Prompt 并渲染
        String dimTemplate = promptGenerator.generateDimensionPrompt(dimConfig, dimension);
        String rendered = datasetAdapter.renderPrompt(dimTemplate, sample, fieldMapping);

        // 2. 拆分 system/user（利用 Prompt Cache 前缀缓存）
        String[] parts = splitSystemUser(rendered);
        String systemPart = parts[0];
        String userPart = parts[1];

        // 3. 调裁判模型
        LlmApiClient.ChatResponse chatResponse;
        if (StrUtil.isNotBlank(systemPart)) {
            chatResponse = llmApiClient.chatWithUsage(judgeModel, systemPart, userPart);
        } else {
            chatResponse = llmApiClient.chatWithUsage(judgeModel, null, rendered);
        }
        String aiResponse = chatResponse.getContent();

        // 4. 解析单维度结果
        PromptGenerator.DimensionResult dr = promptGenerator.parseDimensionResponse(aiResponse, dimension);
        return new MetricResult(dimName, dr.getScore(), dr.isBadcase(), dr.getReason());
    }

    /**
     * 拆分 system / user 以利用 Prompt Cache（前缀缓存）
     * 策略1: 用户手动用 ---CASE--- 分隔
     * 策略2: 自动识别第一个占位符之前的内容作为 system 前缀
     * 策略3: 无占位符则整体作为 user
     */
    private String[] splitSystemUser(String renderedPrompt) {
        String systemPart = null;
        String userPart = renderedPrompt;

        int sepIndex = renderedPrompt.indexOf("---CASE---");
        if (sepIndex >= 0) {
            systemPart = renderedPrompt.substring(0, sepIndex).trim();
            userPart = renderedPrompt.substring(sepIndex + "---CASE---".length()).trim();
            return new String[]{systemPart, userPart};
        }

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\{[a-zA-Z_][a-zA-Z0-9_:]*\\}|\\{[a-zA-Z_][a-zA-Z0-9_:]*\\}").matcher(renderedPrompt);
        if (m.find()) {
            int firstPlaceholderStart = m.start();
            if (firstPlaceholderStart > 0) {
                String beforePlaceholder = renderedPrompt.substring(0, firstPlaceholderStart);
                int lastDoubleNewline = beforePlaceholder.lastIndexOf("\n\n");
                int lastSingleNewline = beforePlaceholder.lastIndexOf("\n");
                int cutPoint = 0;
                if (lastDoubleNewline > 0) {
                    cutPoint = lastDoubleNewline;
                } else if (lastSingleNewline > 50) {
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
}
