package com.eval.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 名称重复检测结果。
 * 评估器(Prompt)：name 全局唯一 —— exists=true 时不允许重复创建。
 * 数据集(Dataset)：同名称合法多版本 —— versionCount 表示当前已有版本数，
 * 再次上传会创建 v(versionCount+1)；若传入 nextVersion 已存在则属于「完全重复」，应阻止。
 */
@Data
public class NameCheckResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 该名称是否已存在 */
    private boolean exists;

    /** 当前已存在的版本数量（评估器恒为 1 或 0） */
    private int versionCount;

    /** 已存在的最新版本号（无则 0） */
    private int latestVersion;

    /** 下次创建将得到的版本号（无则 1） */
    private int nextVersion;

    /** 目标版本是否已被占用（用于编辑重命名校验） */
    private boolean targetVersionTaken;
}