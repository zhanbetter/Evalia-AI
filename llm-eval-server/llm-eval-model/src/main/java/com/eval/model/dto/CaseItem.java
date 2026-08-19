package com.eval.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 分片内的单条评测任务项（序列化为 JSON 存入 shard_data）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CaseItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型配置ID */
    private Long modelConfigId;

    /** 数据集条目ID */
    private Long datasetItemId;
}
