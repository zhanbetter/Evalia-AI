package com.eval.web.controller;

import com.eval.common.auth.AuthContext;
import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.ModelConfigDTO;
import com.eval.model.entity.EvalModelConfig;
import com.eval.service.ModelConfigService;
import com.eval.web.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 模型管理
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    @PostMapping
    public Result<EvalModelConfig> add(@Valid @RequestBody ModelConfigDTO dto,
                                       @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        return Result.success(modelConfigService.add(dto, ctx.getUserId()));
    }

    @GetMapping
    public Result<PageResult<EvalModelConfig>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) Integer status) {
        return Result.success(modelConfigService.list(page, size, modelType, status));
    }

    @PutMapping("/{id}")
    public Result<EvalModelConfig> update(@PathVariable Long id, @Valid @RequestBody ModelConfigDTO dto) {
        return Result.success(modelConfigService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestAttribute(value = AuthInterceptor.AUTH_ATTR) AuthContext ctx) {
        modelConfigService.deleteById(id, ctx);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<String> testConnection(@PathVariable Long id) {
        return Result.success(modelConfigService.testConnection(id));
    }
}
