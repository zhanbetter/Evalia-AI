package com.eval.service;

import com.eval.common.auth.AuthContext;
import com.eval.common.result.PageResult;
import com.eval.model.dto.ModelConfigDTO;
import com.eval.model.entity.EvalModelConfig;

public interface ModelConfigService {

    /**
     * 添加模型配置
     * @param createdBy 创建者ID（eval_user.id），用于删除保护
     */
    EvalModelConfig add(ModelConfigDTO dto, Long createdBy);

    /**
     * 模型列表
     */
    PageResult<EvalModelConfig> list(int page, int size, String modelType, Integer status);

    /**
     * 修改模型配置
     */
    EvalModelConfig update(Long id, ModelConfigDTO dto);

    /**
     * 删除模型（仅创建者或管理员可删）
     */
    void deleteById(Long id, AuthContext ctx);

    /**
     * 测试模型连通性
     */
    String testConnection(Long id);
}
