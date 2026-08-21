package com.eval.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eval.common.auth.AuthContext;
import com.eval.common.exception.BusinessException;
import com.eval.common.result.PageResult;
import com.eval.common.util.EncryptUtil;
import com.eval.common.util.OwnershipUtil;
import com.eval.dao.mapper.EvalModelConfigMapper;
import com.eval.model.dto.ModelConfigDTO;
import com.eval.model.entity.EvalModelConfig;
import com.eval.service.ModelConfigService;
import com.eval.service.LlmApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    private final EvalModelConfigMapper modelConfigMapper;
    private final LlmApiClient llmApiClient;
    private final EncryptUtil encryptUtil;

    @Override
    public EvalModelConfig add(ModelConfigDTO dto, Long createdBy) {
        EvalModelConfig config = new EvalModelConfig();
        config.setName(dto.getName());
        config.setProvider(dto.getProvider());
        config.setApiBase(dto.getApiBase());
        config.setApiKey(encryptUtil.encrypt(dto.getApiKey()));
        config.setModelId(dto.getModelId());
        config.setModelType(validModelType(dto.getModelType()));
        config.setTemperature(dto.getTemperature() != null ? BigDecimal.valueOf(dto.getTemperature()) : BigDecimal.valueOf(0.7));
        config.setMaxTokens(dto.getMaxTokens() != null ? dto.getMaxTokens() : 2048);
        config.setStatus(1);
        config.setCreatedBy(createdBy);
        config.setCreatedAt(LocalDateTime.now());
        modelConfigMapper.insert(config);
        log.info("模型配置添加成功: id={}, name={}", config.getId(), config.getName());
        return config;
    }

    @Override
    public PageResult<EvalModelConfig> list(int page, int size, String modelType, Integer status) {
        Page<EvalModelConfig> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<EvalModelConfig> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(modelType)) {
            wrapper.and(w -> w.eq(EvalModelConfig::getModelType, modelType)
                              .or().eq(EvalModelConfig::getModelType, "both"));
        }
        if (status != null) {
            wrapper.eq(EvalModelConfig::getStatus, status);
        }
        wrapper.orderByDesc(EvalModelConfig::getCreatedAt);
        Page<EvalModelConfig> result = modelConfigMapper.selectPage(pageObj, wrapper);
        result.getRecords().forEach(m -> m.setApiKey(encryptUtil.mask(m.getApiKey())));
        return new PageResult<>(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public EvalModelConfig update(Long id, ModelConfigDTO dto) {
        EvalModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("模型配置不存在");
        }
        config.setName(dto.getName());
        config.setProvider(dto.getProvider());
        config.setApiBase(dto.getApiBase());
        // 只有填了新key才更新，否则保留原key（前端编辑时apiKey可能是脱敏的）
        if (StrUtil.isNotBlank(dto.getApiKey()) && !dto.getApiKey().contains("****")) {
            config.setApiKey(encryptUtil.encrypt(dto.getApiKey()));
        }
        config.setModelId(dto.getModelId());
        config.setModelType(validModelType(dto.getModelType()));
        if (dto.getTemperature() != null) {
            config.setTemperature(BigDecimal.valueOf(dto.getTemperature()));
        }
        if (dto.getMaxTokens() != null) {
            config.setMaxTokens(dto.getMaxTokens());
        }
        modelConfigMapper.updateById(config);
        return config;
    }

    /** 校验并归一化模型类型 */
    private String validModelType(String type) {
        if ("judge".equals(type)) return "judge";
        if ("both".equals(type)) return "both";
        return "evaluated";
    }

    @Override
    public void deleteById(Long id, AuthContext ctx) {
        EvalModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) throw new BusinessException("模型配置不存在");
        OwnershipUtil.assertCanDelete(config.getCreatedBy(), ctx, "模型");
        modelConfigMapper.deleteById(id);
        log.info("模型配置删除: id={}", id);
    }

    @Override
    public String testConnection(Long id) {
        EvalModelConfig config = modelConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("模型配置不存在");
        }
        try {
            String response = llmApiClient.chat(config, "你好，请回复'连接成功'");
            return "连通性测试成功，模型回复: " + response;
        } catch (Exception e) {
            log.error("模型连通性测试失败: id={}", id, e);
            throw new BusinessException("连通性测试失败: " + e.getMessage());
        }
    }
}
