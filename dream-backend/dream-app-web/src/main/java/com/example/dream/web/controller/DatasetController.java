package com.example.dream.web.controller;

import com.example.dream.common.context.UserContext;
import com.example.dream.common.vo.Result;
import com.example.dream.service.biz.KnowledgeBaseBizService;
import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.kb.CreateKbBO;
import com.example.dream.service.biz.bo.kb.DocFilterBO;
import com.example.dream.service.biz.bo.kb.DocItemBO;
import com.example.dream.service.biz.bo.kb.KnowledgeBaseBO;
import com.example.dream.service.biz.bo.kb.ListDocQueryBO;
import com.example.dream.service.biz.bo.kb.UpdateKbBO;
import com.example.dream.web.vo.kb.CreateKbReqVO;
import com.example.dream.web.vo.kb.DeleteKbReqVO;
import com.example.dream.web.vo.kb.DocFilterVO;
import com.example.dream.web.vo.kb.DocItemVO;
import com.example.dream.web.vo.kb.DocListVO;
import com.example.dream.web.vo.kb.KbListVO;
import com.example.dream.web.vo.kb.KnowledgeBaseVO;
import com.example.dream.web.vo.kb.UpdateKbReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库（dataset）相关接口，对齐 RagFlow /api/v1/datasets 系列接口。
 *
 * <ul>
 *   <li>POST   /api/v1/datasets                         创建知识库</li>
 *   <li>GET    /api/v1/datasets                         知识库列表</li>
 *   <li>GET    /api/v1/datasets/{datasetId}             知识库详情</li>
 *   <li>PUT    /api/v1/datasets/{datasetId}             更新知识库</li>
 *   <li>DELETE /api/v1/datasets                         删除知识库</li>
 *   <li>GET    /api/v1/datasets/{datasetId}/documents   文档列表 / 过滤聚合</li>
 * </ul>
 *
 * @author dream
 */
@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final KnowledgeBaseBizService knowledgeBaseBizService;

    /**
     * 创建知识库，对应 RagFlow POST /datasets。
     */
    @PostMapping
    public Result<KnowledgeBaseVO> create(@RequestBody CreateKbReqVO req) {
        CreateKbBO bo = new CreateKbBO();
        bo.setName(req.getName());
        bo.setDescription(req.getDescription());
        bo.setPermission(req.getPermission());
        bo.setChunkMethod(req.getChunkMethod());
        bo.setParserConfig(req.getParserConfig());
        KnowledgeBaseBO created = knowledgeBaseBizService.createDataset(bo, UserContext.getUserId());
        return Result.success(toVO(created));
    }

    /**
     * 知识库列表，对应 RagFlow GET /datasets。
     */
    @GetMapping
    public Result<KbListVO> list(@RequestParam(value = "name", required = false) String name,
                                 @RequestParam(value = "keywords", required = false) String keywords,
                                 @RequestParam(value = "page", defaultValue = "1") int page,
                                 @RequestParam(value = "page_size", defaultValue = "30") int pageSize,
                                 @RequestParam(value = "orderby", defaultValue = "create_time") String orderby,
                                 @RequestParam(value = "desc", defaultValue = "true") boolean desc) {
        PageResultBO<KnowledgeBaseBO> result = knowledgeBaseBizService.listDatasets(
                name, keywords, page, pageSize, orderby, desc, UserContext.getUserId());
        KbListVO vo = new KbListVO();
        vo.setData(result.getData().stream().map(this::toVO).toList());
        vo.setTotal(result.getTotal());
        return Result.success(vo);
    }

    /**
     * 知识库详情，对应 RagFlow GET /datasets/{dataset_id}。
     */
    @GetMapping("/{datasetId}")
    public Result<KnowledgeBaseVO> get(@PathVariable("datasetId") Long datasetId) {
        KnowledgeBaseBO bo = knowledgeBaseBizService.getDataset(datasetId, UserContext.getUserId());
        return Result.success(toVO(bo));
    }

    /**
     * 更新知识库，对应 RagFlow PUT /datasets/{dataset_id}。
     */
    @PutMapping("/{datasetId}")
    public Result<KnowledgeBaseVO> update(@PathVariable("datasetId") Long datasetId,
                                          @RequestBody UpdateKbReqVO req) {
        UpdateKbBO bo = new UpdateKbBO();
        bo.setName(req.getName());
        bo.setDescription(req.getDescription());
        bo.setPermission(req.getPermission());
        bo.setChunkMethod(req.getChunkMethod());
        bo.setParserConfig(req.getParserConfig());
        KnowledgeBaseBO updated = knowledgeBaseBizService.updateDataset(datasetId, bo, UserContext.getUserId());
        return Result.success(toVO(updated));
    }

    /**
     * 删除知识库，对应 RagFlow DELETE /datasets。
     */
    @DeleteMapping
    public Result<Integer> delete(@RequestBody DeleteKbReqVO req) {
        int count = knowledgeBaseBizService.deleteDatasets(
                req.getIds(), Boolean.TRUE.equals(req.getDeleteAll()), UserContext.getUserId());
        return Result.success(count);
    }

    /**
     * 知识库下文档列表，对应 RagFlow GET /datasets/{dataset_id}/documents。
     *
     * <p>type=filter 时对应 RagFlow list_docs 的过滤聚合分支，返回
     * {@code {"total": n, "filter": {suffix, run_status, metadata}}}；
     * 否则返回文档分页列表 {@code {"total": n, "docs": [...]}}。</p>
     */
    @GetMapping("/{datasetId}/documents")
    public Result<?> listDocs(@PathVariable("datasetId") Long datasetId,
                              @RequestParam(value = "type", required = false) String type,
                              @RequestParam(value = "page", defaultValue = "1") int page,
                              @RequestParam(value = "page_size", defaultValue = "30") int pageSize,
                              @RequestParam(value = "orderby", defaultValue = "create_time") String orderby,
                              @RequestParam(value = "desc", defaultValue = "true") boolean desc,
                              @RequestParam(value = "keywords", required = false) String keywords,
                              @RequestParam(value = "suffix", required = false) List<String> suffix,
                              @RequestParam(value = "types", required = false) List<String> types,
                              @RequestParam(value = "run", required = false) List<Integer> run) {
        ListDocQueryBO query = new ListDocQueryBO();
        query.setKbId(datasetId);
        query.setPage(page);
        query.setPageSize(pageSize);
        query.setOrderby(orderby);
        query.setDesc(desc);
        query.setKeywords(keywords);
        query.setSuffix(suffix);
        query.setTypes(types);
        query.setRun(run);

        // 对应 Python: if request.args.get("type") == "filter": _get_doc_filters_with_request(...)
        if ("filter".equals(type)) {
            PageResultBO<DocFilterBO> filterResult = knowledgeBaseBizService.getDocFilters(query, UserContext.getUserId());
            DocFilterVO vo = new DocFilterVO();
            vo.setTotal(filterResult.getTotal());
            DocFilterBO bo = filterResult.getData().isEmpty() ? new DocFilterBO() : filterResult.getData().get(0);
            DocFilterVO.FilterBody body = new DocFilterVO.FilterBody();
            body.setSuffix(bo.getSuffix());
            body.setRunStatus(bo.getRunStatus());
            body.setMetadata(bo.getMetadata());
            vo.setFilter(body);
            return Result.success(vo);
        }

        PageResultBO<DocItemBO> result = knowledgeBaseBizService.listDocs(query, UserContext.getUserId());
        DocListVO vo = new DocListVO();
        vo.setTotal(result.getTotal());
        vo.setDocs(result.getData().stream().map(this::toDocVO).toList());
        return Result.success(vo);
    }

    // ==================== VO 转换 ====================

    /**
     * 知识库 BO 转 VO（id 转字符串避免前端精度丢失）。
     */
    private KnowledgeBaseVO toVO(KnowledgeBaseBO bo) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(bo.getId() == null ? null : String.valueOf(bo.getId()));
        vo.setName(bo.getName());
        vo.setDescription(bo.getDescription());
        vo.setUserId(bo.getUserId());
        vo.setPermission(bo.getPermission());
        vo.setChunkMethod(bo.getChunkMethod());
        vo.setDocNum(bo.getDocNum());
        vo.setTokenNum(bo.getTokenNum());
        vo.setChunkNum(bo.getChunkNum());
        vo.setCreatedTime(bo.getCreatedTime());
        vo.setModifiedTime(bo.getModifiedTime());
        return vo;
    }

    /**
     * 文档 BO 转 VO（id / kbId 转字符串避免前端精度丢失）。
     */
    private DocItemVO toDocVO(DocItemBO bo) {
        DocItemVO vo = new DocItemVO();
        vo.setId(bo.getId() == null ? null : String.valueOf(bo.getId()));
        vo.setName(bo.getName());
        vo.setKbId(bo.getKbId() == null ? null : String.valueOf(bo.getKbId()));
        vo.setChunkMethod(bo.getChunkMethod());
        vo.setType(bo.getType());
        vo.setSuffix(bo.getSuffix());
        vo.setSize(bo.getSize());
        vo.setChunkCount(bo.getChunkCount());
        vo.setTokenCount(bo.getTokenCount());
        vo.setRun(bo.getRun());
        vo.setProgress(bo.getProgress());
        vo.setProgressMsg(bo.getProgressMsg());
        vo.setStatus(bo.getStatus());
        vo.setErrorMsg(bo.getErrorMsg());
        vo.setCreatedTime(bo.getCreatedTime());
        vo.setModifiedTime(bo.getModifiedTime());
        return vo;
    }
}