package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.DocumentBizService;
import com.example.dream.service.biz.bo.DocumentBO;
import com.example.dream.service.biz.bo.IngestBO;
import com.example.dream.web.vo.DocumentVO;
import com.example.dream.web.vo.IngestReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentBizService documentBizService;

    /**
     * 上传文档到指定数据集。
     * 对应 RagFlow upload_document：根据 type 分发 local/web/empty，当前仅实现 local。
     *
     * @param kbId 数据集 ID（路径参数）
     * @param files     上传文件（表单字段 file，支持多文件）
     * @return 上传成功的文档列表
     */
    @PostMapping("/upload")
    public Result<List<DocumentVO>> upload(
            @RequestParam("kbId") Long kbId,
            @RequestParam(value = "file", required = false) List<MultipartFile> files) {

        // 对应 Python: return await _upload_local_documents(kb, tenant_id)
        List<DocumentBO> docs = documentBizService.uploadLocalDocuments(kbId, files, UserContext.getUserId());
        return Result.success(toVOList(docs));
    }

    /**
     * 触发文档解析 / 运行状态变更。
     * 对应 RagFlow document_api.py：POST /documents/ingest。
     *
     * @param req 解析请求体（doc_ids / run / delete / apply_kb）
     * @return 处理结果，成功返回 true（对应 Python get_json_result(data=True)）
     */
    @PostMapping("/ingest")
    public Result<Boolean> ingest(@RequestBody IngestReqVO req) {
        // 对应 Python: error_code, error_message = _run_sync(user_id, req)
        IngestBO bo = new IngestBO();
        bo.setDocIds(req.getDocIds());
        bo.setRun(req.getRun());
        bo.setDelete(Boolean.TRUE.equals(req.getDelete()));
        bo.setApplyKb(Boolean.TRUE.equals(req.getApplyKb()));

        documentBizService.ingest(bo, UserContext.getUserId());
        // 对应 Python: return get_json_result(data=True)
        return Result.success(Boolean.TRUE);
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
        vo.setKdId(bo.getKbId());
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