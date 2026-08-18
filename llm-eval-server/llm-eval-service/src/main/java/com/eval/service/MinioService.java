package com.eval.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * MinIO 文件存储服务
 */
public interface MinioService {

    /**
     * 上传文件，返回文件路径
     */
    String uploadFile(MultipartFile file);

    /**
     * 获取文件下载URL
     */
    String getFileUrl(String filePath);
}
