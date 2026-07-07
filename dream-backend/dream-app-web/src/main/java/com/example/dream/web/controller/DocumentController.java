package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.DocumentBizService;
import com.example.dream.service.biz.bo.DocumentBO;
import com.example.dream.web.vo.DocumentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档相关接口。
 *
 * <p>对应 RagFlow document_api.py：POST /datasets/<dataset_id>/documents。
 * 当前仅实现 local 上传主流程，鉴权使用当前登录用户 userId。</p>
 *
 * @author dream
 */
@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentBizService documentBizService;

    /**
     * 上传文档到指定数据集。
     * 对应 RagFlow upload_document：根据 type 分发 local/web/empty，当前仅实现 local。
     *
     * @param datasetId  数据集 ID（路径参数）
     * @param type       上传类型，默认 local
     * @param files      上传文件（表单字段 file，支持多文件）
     * @return 上传成功的文档列表
     */
    @PostMapping("/{datasetId}/documents")
    public Result<List<DocumentVO>> uploadDocument(
            @PathVariable("datasetId") String datasetId,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "file", required = false) List<MultipartFile> files) {

        // 对应 Python: return await _upload_local_documents(kb, tenant_id)
        List<DocumentBO> docs = documentBizService.uploadLocalDocuments(datasetId, files, UserContext.getUserId());
        return Result.success(toVOList(docs));
    }

    /**
     * BO 列表转 VO 列表。
     */
    private List<DocumentVO> toVOList(List<DocumentBO> docs) {
        return docs.stream().map(this::toVO).toList();
    }

    /**
     * 单个 BO 转 VO。
     */
    private DocumentVO toVO(DocumentBO bo) {
        DocumentVO vo = new DocumentVO();
        vo.setId(bo.getId());
        vo.setName(bo.getName());
        vo.setDatasetId(bo.getDatasetId());
        vo.setChunkMethod(bo.getChunkMethod());
        vo.setChunkCount(bo.getChunkCount());
        vo.setTokenCount(bo.getTokenCount());
        vo.setType(bo.getType());
        vo.setSuffix(bo.getSuffix());
        vo.setSize(bo.getSize());
        vo.setLocation(bo.getLocation());
        vo.setRun(bo.getRun());
        return vo;
    }
}