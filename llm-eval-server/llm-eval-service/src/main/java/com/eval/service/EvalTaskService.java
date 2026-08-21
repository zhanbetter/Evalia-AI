package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.EvalTaskDTO;
import com.eval.model.entity.EvalTask;

public interface EvalTaskService {

    /**
     * 创建评测任务
     * @param createdBy 创建者ID（eval_user.id），用于数据归属
     */
    EvalTask create(EvalTaskDTO dto, Long createdBy);

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

    /**
     * 重试失败分片
     */
    void retryFailedShards(Long taskId);
}
