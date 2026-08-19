package com.eval.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分片消息（发送到 Kafka eval-shard-execute topic）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShardMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务ID */
    private Long taskId;

    /** 分片序号 */
    private Integer shardIndex;

    /** 该分片内的评测 case 列表 */
    private List<CaseItem> cases;

    /** 该分片总 case 数 */
    private int totalCount;
}
