package com.eval.web.controller;

import com.eval.common.result.Result;
import com.eval.service.EvalResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 评测报告 - 支持HTML预览和下载
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final EvalResultService evalResultService;

    /**
     * HTML格式预览
     */
    @GetMapping(value = "/{taskId}/preview", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String preview(@PathVariable Long taskId) {
        return evalResultService.generateReport(taskId);
    }

    /**
     * JSON格式返回HTML内容（供前端渲染）
     */
    @GetMapping("/{taskId}/html")
    public Result<String> html(@PathVariable Long taskId) {
        return Result.success(evalResultService.generateReport(taskId));
    }

    /**
     * 下载HTML报告文件
     */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long taskId) {
        String report = evalResultService.generateReport(taskId);
        byte[] content = report.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=eval_report_" + taskId + ".html")
                .contentType(MediaType.TEXT_HTML)
                .contentLength(content.length)
                .body(content);
    }
}
