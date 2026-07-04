package com.example.dream.integration.service.minio;

import java.io.InputStream;
import java.util.List;

/**
 * 对象存储（OSS）通用操作接口。
 * <p>对底层 MinIO 的桶与对象操作进行封装，供上层业务调用。</p>
 *
 * @author dream
 */
public interface OssService {

    /**
     * 判断桶是否存在。
     *
     * @param bucket 桶名称
     * @return 存在返回 true
     */
    boolean bucketExists(String bucket);

    /**
     * 创建桶（若不存在）。
     *
     * @param bucket 桶名称
     */
    void createBucket(String bucket);

    /**
     * 删除桶。
     *
     * @param bucket 桶名称
     */
    void removeBucket(String bucket);

    /**
     * 上传对象至默认桶。
     *
     * @param objectName  对象名（可含路径，如 avatar/1.png）
     * @param stream      文件输入流
     * @param size        文件大小（字节）
     * @param contentType 内容类型（MIME）
     * @return 对象名
     */
    String putObject(String objectName, InputStream stream, long size, String contentType);

    /**
     * 上传对象至指定桶。
     *
     * @param bucket      桶名称
     * @param objectName  对象名
     * @param stream      文件输入流
     * @param size        文件大小（字节）
     * @param contentType 内容类型（MIME）
     * @return 对象名
     */
    String putObject(String bucket, String objectName, InputStream stream, long size, String contentType);

    /**
     * 从默认桶下载对象。
     *
     * @param objectName 对象名
     * @return 对象输入流（使用后需关闭）
     */
    InputStream getObject(String objectName);

    /**
     * 从指定桶下载对象。
     *
     * @param bucket     桶名称
     * @param objectName 对象名
     * @return 对象输入流（使用后需关闭）
     */
    InputStream getObject(String bucket, String objectName);

    /**
     * 从默认桶删除对象。
     *
     * @param objectName 对象名
     */
    void removeObject(String objectName);

    /**
     * 从指定桶删除对象。
     *
     * @param bucket     桶名称
     * @param objectName 对象名
     */
    void removeObject(String bucket, String objectName);

    /**
     * 列出指定桶下的对象名称。
     *
     * @param bucket 桶名称
     * @param prefix 名称前缀（可为 null）
     * @return 对象名称列表
     */
    List<String> listObjects(String bucket, String prefix);

    /**
     * 获取默认桶中对象的预签名下载 URL（GET），使用默认过期时间。
     *
     * @param objectName 对象名
     * @return 预签名 URL
     */
    String getPresignedUrl(String objectName);

    /**
     * 获取指定桶中对象的预签名下载 URL（GET）。
     *
     * @param bucket        桶名称
     * @param objectName    对象名
     * @param expirySeconds 过期时间（秒）
     * @return 预签名 URL
     */
    String getPresignedUrl(String bucket, String objectName, int expirySeconds);
}