package com.eval.service;

import com.eval.common.auth.AuthContext;
import com.eval.common.result.PageResult;
import com.eval.model.dto.PromptDTO;
import com.eval.model.entity.EvalPrompt;
import com.eval.model.entity.EvalPromptVersion;

import java.util.List;

public interface PromptService {

    /** @param createdBy 创建者ID（eval_user.id），用于删除保护 */
    EvalPrompt add(PromptDTO dto, Long createdBy);

    /** 检测评估器名称是否已被占用（评估器名称全局唯一） */
    com.eval.model.dto.NameCheckResult checkName(String name, Long excludePromptId);

    PageResult<EvalPrompt> list(int page, int size);

    EvalPrompt update(Long id, PromptDTO dto);

    /** 删除评估器（仅创建者或管理员可删） */
    void deleteById(Long id, AuthContext ctx);

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
     * 并行润色评估器维度配置（异步任务用）
     * @param modelId 用于润色的模型ID
     * @param dimensionsConfig 当前维度配置 JSON
     * @param progress 进度回调：参数为「完成数/总数：维度名」，可为 null
     * @return 润色后的维度配置 JSON
     */
    String polishParallel(Long modelId, String dimensionsConfig, java.util.function.Consumer<String> progress);

    /**
     * 将自然语言评测标准识别为结构化维度配置
     * @param modelId 用于识别的模型ID
     * @param text 用户自然语言描述的评测标准
     * @return 结构化的 dimensions_config JSON
     */
    String parseToDimensions(Long modelId, String text);
}
