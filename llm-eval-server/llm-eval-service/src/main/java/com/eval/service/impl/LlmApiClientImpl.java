package com.eval.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.eval.common.exception.BusinessException;
import com.eval.common.util.EncryptUtil;
import com.eval.model.entity.EvalModelConfig;
import com.eval.service.LlmApiClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LlmApiClientImpl implements LlmApiClient {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final EncryptUtil encryptUtil;

    public LlmApiClientImpl(EncryptUtil encryptUtil) {
        this.encryptUtil = encryptUtil;
    }

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    @Override
    public String chat(EvalModelConfig config, String prompt) {
        ChatResponse response = chatWithUsage(config, prompt);
        return response.getContent();
    }

    @Override
    public String chat(EvalModelConfig config, String systemPrompt, String userPrompt) {
        ChatResponse response = chatWithUsage(config, systemPrompt, userPrompt);
        return response.getContent();
    }

    @Override
    public ChatResponse chatWithUsage(EvalModelConfig config, String prompt) {
        // 兼容旧调用：全部作为 user message
        return chatWithUsage(config, null, prompt);
    }

    @Override
    public ChatResponse chatWithUsage(EvalModelConfig config, String systemPrompt, String userPrompt) {
        try {
            // 构建请求体（OpenAI Chat Completions 格式）
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModelId());

            // 构建 messages：system（固定前缀，可缓存）+ user（动态内容）
            java.util.List<Map<String, String>> messages = new java.util.ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                Map<String, String> sysMsg = new HashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", systemPrompt);
                messages.add(sysMsg);
            }
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);
            requestBody.put("temperature", config.getTemperature().doubleValue());
            requestBody.put("max_tokens", config.getMaxTokens());

            String jsonBody = JSONUtil.toJsonStr(requestBody);

            // 构建请求 URL
            String url = config.getApiBase();
            if (!url.endsWith("/")) {
                url += "/";
            }
            url += "v1/chat/completions";

            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(jsonBody, JSON_MEDIA_TYPE));

            // 设置认证头（apiKey 为加密存储，调用时解密）
            builder.header("Authorization", "Bearer " + encryptUtil.decrypt(config.getApiKey()));
            builder.header("Content-Type", "application/json");

            long startTime = System.currentTimeMillis();
            try (Response response = httpClient.newCall(builder.build()).execute()) {
                long latency = System.currentTimeMillis() - startTime;

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "unknown";
                    log.error("LLM API调用失败: status={}, body={}", response.code(), errorBody);
                    throw new BusinessException("模型API调用失败: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject jsonObj = JSONUtil.parseObj(responseBody);

                // 解析响应
                String content = jsonObj
                        .getByPath("choices[0].message.content", String.class);
                if (content == null) {
                    content = "";
                }

                // 解析 usage
                JSONObject usage = jsonObj.getJSONObject("usage");
                int promptTokens = usage != null ? usage.getInt("prompt_tokens", 0) : 0;
                int completionTokens = usage != null ? usage.getInt("completion_tokens", 0) : 0;
                int totalTokens = usage != null ? usage.getInt("total_tokens", 0) : 0;

                // 解析 Prompt Cache 命中 token 数
                // OpenAI: usage.prompt_tokens_details.cached_tokens
                // Anthropic: usage.cache_read_input_tokens
                int cachedTokens = 0;
                if (usage != null) {
                    try {
                        JSONObject details = usage.getJSONObject("prompt_tokens_details");
                        if (details != null) {
                            cachedTokens = details.getInt("cached_tokens", 0);
                        }
                    } catch (Exception ignored) {}
                    // Anthropic 格式
                    if (cachedTokens == 0) {
                        cachedTokens = usage.getInt("cache_read_input_tokens", 0);
                    }
                }

                log.debug("LLM调用成功: model={}, latency={}ms, tokens={}, cached={}", config.getModelId(), latency, totalTokens, cachedTokens);
                return new ChatResponse(content, promptTokens, completionTokens, totalTokens, (int) latency, cachedTokens);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM API调用异常: model={}", config.getModelId(), e);
            throw new BusinessException("模型API调用异常: " + e.getMessage());
        }
    }
}
