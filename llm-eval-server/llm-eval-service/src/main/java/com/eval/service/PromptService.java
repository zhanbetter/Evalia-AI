package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.PromptDTO;
import com.eval.model.entity.EvalPrompt;
import com.eval.model.entity.EvalPromptVersion;

import java.util.List;

public interface PromptService {

    EvalPrompt add(PromptDTO dto);

    PageResult<EvalPrompt> list(int page, int size);

    EvalPrompt update(Long id, PromptDTO dto);

    void deleteById(Long id);

    EvalPrompt getById(Long id);

    /** 评估器版本历史（旧版本快照，新版本在前） */
    List<EvalPromptVersion> listVersions(Long promptId);

    /** 获取单个历史版本详情 */
    EvalPromptVersion getVersion(Long promptId, Integer version);

    /** 恢复到指定历史版本（内容回写 + 版本号自增） */
    EvalPrompt restoreVersion(Long promptId, Integer version);

    /**
     * AI 润色评估器维度配置
     * @param modelId 用于润色的模型ID（复用已配置的模型）
     * @param dimensionsConfig 当前维度配置 JSON
     * @return 润色后的维度配置 JSON
     */
    String polish(Long modelId, String dimensionsConfig);

    /**
     * 将自然语言评测标准识别为结构化维度配置
     * @param modelId 用于识别的模型ID
     * @param text 用户自然语言描述的评测标准
     * @return 结构化的 dimensions_config JSON
     */
    String parseToDimensions(Long modelId, String text);
}
