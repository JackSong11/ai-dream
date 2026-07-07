package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.DocumentBO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档业务编排服务。
 *
 * <p>对应 RagFlow document_api.py 中 upload_document 的 local 上传主流程
 * （_upload_local_documents + FileService.upload_document）。</p>
 */
public interface DocumentBizService {

    /**
     * 上传本地文档到指定数据集（local 主流程）。
     *
     * @param datasetId 数据集 / 知识库 ID（对应 RagFlow dataset_id）
     * @param files     上传的文件列表（对应 RagFlow files.getlist("file")）
     * @param userId    当前登录用户 ID（用于鉴权与创建人，替代 RagFlow tenant_id）
     * @return 上传成功的文档列表
     */
    List<DocumentBO> uploadLocalDocuments(String datasetId,
                                          List<MultipartFile> files,
                                          String userId);
}