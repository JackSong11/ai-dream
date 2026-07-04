package com.example.dream.integration.service.minio.impl;

import com.example.dream.integration.service.minio.OssService;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveBucketArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.http.Method;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.simpleframework.xml.core.Resolve;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 MinIO 的 {@link OssService} 实现。
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioServiceImpl implements OssService {

    @Value("${minio.bucket:dream}")
    private String bucket;

    @Value("${minio.presign-expiry-seconds:7200}")
    private int presignExpirySeconds;

    private final MinioClient minioClient;

    @Override
    public boolean bucketExists(String bucket) {
        try {
            return minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        } catch (Exception e) {
            throw new RuntimeException("判断桶是否存在失败: " + bucket, e);
        }
    }

    @Override
    public void createBucket(String bucket) {
        try {
            if (!bucketExists(bucket)) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("创建桶成功: {}", bucket);
            }
        } catch (Exception e) {
            throw new RuntimeException("创建桶失败: " + bucket, e);
        }
    }

    @Override
    public void removeBucket(String bucket) {
        try {
            minioClient.removeBucket(RemoveBucketArgs.builder().bucket(bucket).build());
            log.info("删除桶成功: {}", bucket);
        } catch (Exception e) {
            throw new RuntimeException("删除桶失败: " + bucket, e);
        }
    }

    @Override
    public String putObject(String objectName, InputStream stream, long size, String contentType) {
        return putObject(null, objectName, stream, size, contentType);
    }

    @Override
    public String putObject(String bucket, String objectName, InputStream stream, long size, String contentType) {
        String targetBucket = resolveBucket(bucket);
        try {
            createBucket(targetBucket);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(targetBucket)
                    .object(objectName)
                    .stream(stream, size, -1)
                    .contentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                    .build());
            log.info("上传对象成功: bucket={}, object={}", targetBucket, objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("上传对象失败: " + objectName, e);
        }
    }

    @Override
    public InputStream getObject(String objectName) {
        return getObject(resolveBucket(null), objectName);
    }

    @Override
    public InputStream getObject(String bucket, String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(resolveBucket(bucket))
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("下载对象失败: " + objectName, e);
        }
    }

    @Override
    public void removeObject(String objectName) {
        removeObject(resolveBucket(null), objectName);
    }

    @Override
    public void removeObject(String bucket, String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(resolveBucket(bucket))
                    .object(objectName)
                    .build());
            log.info("删除对象成功: bucket={}, object={}", bucket, objectName);
        } catch (Exception e) {
            throw new RuntimeException("删除对象失败: " + objectName, e);
        }
    }

    @Override
    public List<String> listObjects(String bucket, String prefix) {
        List<String> names = new ArrayList<>();
        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(resolveBucket(bucket))
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                names.add(result.get().objectName());
            }
            return names;
        } catch (Exception e) {
            throw new RuntimeException("列举对象失败: bucket=" + bucket, e);
        }
    }

    @Override
    public String getPresignedUrl(String objectName) {
        return getPresignedUrl(null, objectName, presignExpirySeconds);
    }

    @Override
    public String getPresignedUrl(String bucket, String objectName, int expirySeconds) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(resolveBucket(bucket))
                    .object(objectName)
                    .expiry(expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("生成预签名 URL 失败: " + objectName, e);
        }
    }

    /**
     * 解析实际使用的桶名：优先使用入参，否则回退到默认桶。
     *
     * @param bucketName 入参桶名
     * @return 实际桶名
     */
    private String resolveBucket(String bucketName) {
        if (StringUtils.hasText(bucketName)) {
            return bucketName;
        }
        if (StringUtils.hasText(bucket)) {
            return bucket;
        }
        throw new IllegalArgumentException("未指定桶名且未配置默认桶 dream.minio.bucket");
    }
}