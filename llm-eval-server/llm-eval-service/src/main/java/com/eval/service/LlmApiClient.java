package com.eval.service;

import com.eval.model.entity.EvalModelConfig;

/**
 * LLM API 客户端（OpenAI 兼容接口）
 */
public interface LlmApiClient {

    /**
     * 调用模型进行对话
     */
    String chat(EvalModelConfig config, String prompt);

    /**
     * 调用模型进行对话（支持 system + user 分离，利用 Prompt Cache）
     * systemPrompt 作为固定前缀可被缓存，userPrompt 每次变化
     */
    String chat(EvalModelConfig config, String systemPrompt, String userPrompt);

    /**
     * 调用模型进行对话，返回原始响应（含 usage 等信息）
     */
    ChatResponse chatWithUsage(EvalModelConfig config, String prompt);

    /**
     * 调用模型进行对话（支持 system + user 分离），返回原始响应
     */
    ChatResponse chatWithUsage(EvalModelConfig config, String systemPrompt, String userPrompt);

    /**
     * 响应结果
     */
    class ChatResponse {
        private String content;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private int latencyMs;
        private int cachedTokens; // Prompt Cache 命中的 token 数

        public ChatResponse() {}

        public ChatResponse(String content, int promptTokens, int completionTokens, int totalTokens, int latencyMs) {
            this(content, promptTokens, completionTokens, totalTokens, latencyMs, 0);
        }

        public ChatResponse(String content, int promptTokens, int completionTokens, int totalTokens, int latencyMs, int cachedTokens) {
            this.content = content;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
            this.latencyMs = latencyMs;
            this.cachedTokens = cachedTokens;
        }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
        public int getLatencyMs() { return latencyMs; }
        public void setLatencyMs(int latencyMs) { this.latencyMs = latencyMs; }
        public int getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(int cachedTokens) { this.cachedTokens = cachedTokens; }
    }
}
