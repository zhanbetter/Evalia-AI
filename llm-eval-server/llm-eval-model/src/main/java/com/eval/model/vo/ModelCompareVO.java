package com.eval.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型对比 VO：按数据集样本分组，展示每个模型的回答 + AI 判定
 */
@Data
public class ModelCompareVO {

    /** 数据集条目ID */
    private Long datasetItemId;

    /** 问题 */
    private String question;

    /** 参考答案 */
    private String referenceAnswer;

    /** 上下文 */
    private String context;

    /** 各模型的表现 */
    private List<ModelAnswer> answers = new ArrayList<>();

    @Data
    public static class ModelAnswer {
        /** 模型配置ID */
        private Long modelConfigId;

        /** 模型名称 */
        private String modelName;

        /** 模型回答 */
        private String response;

        /** 响应耗时(ms) */
        private Integer latencyMs;

        /** AI判定: 1-badcase 0-非badcase 空=未判定 */
        private Integer isBadcase;

        /** AI判定理由 */
        private String judgeReason;
    }
}
