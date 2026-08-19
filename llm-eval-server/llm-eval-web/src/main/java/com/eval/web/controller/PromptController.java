package com.eval.web.controller;

import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.PromptDTO;
import com.eval.model.entity.EvalPrompt;
import com.eval.service.PromptService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 评测Prompt管理
 */
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    private final PromptService promptService;

    @PostMapping
    public Result<EvalPrompt> add(@Valid @RequestBody PromptDTO dto) {
        return Result.success(promptService.add(dto));
    }

    @GetMapping
    public Result<PageResult<EvalPrompt>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(promptService.list(page, size));
    }

    @PutMapping("/{id}")
    public Result<EvalPrompt> update(@PathVariable Long id, @Valid @RequestBody PromptDTO dto) {
        return Result.success(promptService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        promptService.deleteById(id);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<EvalPrompt> getById(@PathVariable Long id) {
        return Result.success(promptService.getById(id));
    }

    /** 评估器版本历史（快照列表，新版本在前） */
    @GetMapping("/{id}/versions")
    public Result<java.util.List<com.eval.model.entity.EvalPromptVersion>> listVersions(@PathVariable Long id) {
        return Result.success(promptService.listVersions(id));
    }

    /** 获取单个历史版本详情 */
    @GetMapping("/{id}/versions/{version}")
    public Result<com.eval.model.entity.EvalPromptVersion> getVersion(@PathVariable Long id, @PathVariable Integer version) {
        com.eval.model.entity.EvalPromptVersion v = promptService.getVersion(id, version);
        if (v == null) return Result.success(null);
        return Result.success(v);
    }

    /** 恢复到指定历史版本 */
    @PostMapping("/{id}/versions/{version}/restore")
    public Result<com.eval.model.entity.EvalPrompt> restoreVersion(@PathVariable Long id, @PathVariable Integer version) {
        return Result.success(promptService.restoreVersion(id, version));
    }

    /** 解析Prompt模板中的占位符，支持 ${xxx} 和 {xxx} 两种格式 */
    @GetMapping("/parse-placeholders")
    public Result<java.util.List<String>> parsePlaceholders(@RequestParam String template) {
        java.util.List<String> placeholders = new java.util.ArrayList<>();
        // 匹配 ${xxx} 或 {xxx}，提取变量名
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_:]*)\\}|\\{([a-zA-Z_][a-zA-Z0-9_:]*)\\}").matcher(template);
        while (matcher.find()) {
            // group(1) 是 ${xxx} 中的 xxx, group(2) 是 {xxx} 中的 xxx
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            placeholders.add(name);
        }
        return Result.success(placeholders);
    }

    /** 预览：根据 dimensions_config 自动生成的 Prompt 文本 */
    @PostMapping("/preview")
    public Result<String> previewPrompt(@RequestBody PromptDTO dto) {
        if (dto.getDimensionsConfig() == null || dto.getDimensionsConfig().isEmpty()) {
            return Result.success(dto.getPromptTemplate());
        }
        com.eval.service.PromptGenerator.DimensionsConfig config =
                com.eval.service.PromptGenerator.DimensionsConfig.fromJson(dto.getDimensionsConfig());
        if (config != null && config.getDimensions() != null && !config.getDimensions().isEmpty()) {
            com.eval.service.PromptGenerator generator = new com.eval.service.PromptGenerator();
            return Result.success(generator.generatePrompt(config));
        }
        return Result.success(dto.getPromptTemplate());
    }

    /** AI 润色评估器维度配置 */
    @PostMapping("/polish")
    public Result<String> polish(@RequestBody java.util.Map<String, Object> body) {
        Long modelId = body.get("modelId") != null
                ? Long.valueOf(body.get("modelId").toString()) : null;
        String dimensionsConfig = body.get("dimensionsConfig") != null
                ? body.get("dimensionsConfig").toString() : null;
        return Result.success(promptService.polish(modelId, dimensionsConfig));
    }

    /** 将自然语言评测标准识别为结构化维度配置 */
    @PostMapping("/parse-to-dimensions")
    public Result<String> parseToDimensions(@RequestBody java.util.Map<String, Object> body) {
        Long modelId = body.get("modelId") != null
                ? Long.valueOf(body.get("modelId").toString()) : null;
        String text = body.get("text") != null ? body.get("text").toString() : null;
        return Result.success(promptService.parseToDimensions(modelId, text));
    }
}
