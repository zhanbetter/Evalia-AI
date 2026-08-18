package com.eval.web.controller;

import com.eval.common.result.PageResult;
import com.eval.common.result.Result;
import com.eval.model.dto.EvalTaskDTO;
import com.eval.model.entity.EvalTask;
import com.eval.service.EvalTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 评测任务
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class EvalTaskController {

    private final EvalTaskService evalTaskService;

    @PostMapping
    public Result<EvalTask> create(@Valid @RequestBody EvalTaskDTO dto) {
        return Result.success(evalTaskService.create(dto));
    }

    @GetMapping
    public Result<PageResult<EvalTask>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(evalTaskService.list(page, size));
    }

    @GetMapping("/{id}")
    public Result<EvalTask> getById(@PathVariable Long id) {
        return Result.success(evalTaskService.getById(id));
    }

    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        evalTaskService.start(id);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        evalTaskService.cancel(id);
        return Result.success();
    }

    @GetMapping("/{id}/progress")
    public Result<Integer> getProgress(@PathVariable Long id) {
        return Result.success(evalTaskService.getProgress(id));
    }
}
