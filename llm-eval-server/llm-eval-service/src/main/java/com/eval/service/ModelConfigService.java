package com.eval.service;

import com.eval.common.result.PageResult;
import com.eval.model.dto.ModelConfigDTO;
import com.eval.model.entity.EvalModelConfig;

public interface ModelConfigService {

    /**
     * 添加模型配置
     */
    EvalModelConfig add(ModelConfigDTO dto);

    /**
     * 模型列表
     */
    PageResult<EvalModelConfig> list(int page, int size);

    /**
     * 修改模型配置
     */
    EvalModelConfig update(Long id, ModelConfigDTO dto);

    /**
     * 删除模型
     */
    void deleteById(Long id);

    /**
     * 测试模型连通性
     */
    String testConnection(Long id);
}
