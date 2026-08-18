package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.EvalTaskDTO;
import com.eval.model.entity.EvalTask;

public interface EvalTaskService {

    /**
     * 创建评测任务
     */
    EvalTask create(EvalTaskDTO dto);

    /**
     * 任务列表（分页）
     */
    PageResult<EvalTask> list(int page, int size);

    /**
     * 任务详情
     */
    EvalTask getById(Long id);

    /**
     * 启动评测（发送Kafka消息）
     */
    void start(Long id);

    /**
     * 取消评测
     */
    void cancel(Long id);

    /**
     * 查询进度
     */
    int getProgress(Long id);
}
