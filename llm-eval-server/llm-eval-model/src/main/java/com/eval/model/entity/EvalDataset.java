package com.eval.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 评测数据集
 */
@Data
@TableName("eval_dataset")
public class EvalDataset implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据集名称 */
    private String name;

    /** 版本号，同名称上传自动递增 */
    private Integer version;

    /** 描述 */
    private String description;

    /** MinIO 文件路径 */
    private String filePath;

    /** 文件类型: JSON/CSV/XLSX */
    private String fileType;

    /** 列映射配置JSON */
    private String columnMapping;

    /** 是否含参考答案: 1-有(对照评测), 0-无(自由判断) */
    private Integer hasReference;

    /** 是否含模型结果: 1-有(评测直接读), 0-无(评测时调API生成) */
    private Integer hasModelResponse;

    /** 样本总数 */
    private Integer totalCount;

    /** 创建者ID（eval_user.id）；null=历史无归属数据（仅管理员可删） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
