package com.eval.web.controller;

import com.eval.common.result.Result;
import com.eval.model.vo.AsyncJobVO;
import com.eval.service.AsyncJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异步任务轮询接口
 *
 * 前端提交耗时操作（AI识别成规则 / 润色多维度 / 数据重复检测）得到 jobId 后，
 * 通过本接口轮询进度，完成后取结果：
 *   GET  /api/async-jobs/{id}          任务状态 + 进度
 *   GET  /api/async-jobs/{id}/result   任务结果（仅 COMPLETED 后可取）
 */
@RestController
@RequestMapping("/api/async-jobs")
@RequiredArgsConstructor
public class AsyncJobController {

    private final AsyncJobService asyncJobService;

    /** 查询任务状态/进度 */
    @GetMapping("/{id}")
    public Result<AsyncJobVO> get(@PathVariable Long id) {
        return Result.success(asyncJobService.getJobVO(id));
    }

    /** 获取任务结果（任务未完成时报错） */
    @GetMapping("/{id}/result")
    public Result<Object> getResult(@PathVariable Long id) {
        return Result.success(asyncJobService.getResult(id));
    }
}