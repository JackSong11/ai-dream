package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.dal.po.KbTaskPO;
import com.example.dream.dal.po.KnowledgeBasePO;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.minio.OssService;
import com.example.dream.service.biz.KnowledgeBaseBizService;
import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.kb.CreateKbBO;
import com.example.dream.service.biz.bo.kb.DocFilterBO;
import com.example.dream.service.biz.bo.kb.DocItemBO;
import com.example.dream.service.biz.bo.kb.KnowledgeBaseBO;
import com.example.dream.service.biz.bo.kb.ListDocQueryBO;
import com.example.dream.service.biz.bo.kb.UpdateKbBO;
import com.example.dream.service.core.KbDocumentCoreService;
import com.example.dream.service.core.KbTaskCoreService;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link KnowledgeBaseBizService} 实现，对齐 RagFlow dataset_api_service.py 与 document_api.py 核心逻辑。
 *
 * <p>鉴权模型：RagFlow 使用 tenant_id 做归属校验，本项目使用登录用户 userId 写入
 * knowledge_base.user_id / creator，校验知识库归属当前用户（对应 KnowledgebaseService.accessible）。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseBizServiceImpl implements KnowledgeBaseBizService {

    /**
     * 知识库名称长度限制（字节），对应 RagFlow DATASET_NAME_LIMIT。
     */
    private static final int DATASET_NAME_LIMIT = 128;

    /**
     * 默认解析器，对应 RagFlow parser_id 默认 "naive"。
     */
    private static final String DEFAULT_PARSER_ID = "naive";

    /**
     * 默认权限，对应 RagFlow permission 默认 "me"。
     */
    private static final String DEFAULT_PERMISSION = "me";

    /**
     * 默认解析器配置，对应 RagFlow get_parser_config 的 naive 默认值。
     */
    private static final String DEFAULT_PARSER_CONFIG =
            "{\"chunk_token_num\":512,\"delimiter\":\"\\n!?。；！？\",\"layout_recognize\":\"DeepDOC\","
                    + "\"html4excel\":false,\"raptor\":{\"use_raptor\":false}}";

    /**
     * ES 文档 ID 字段名，对应 RagFlow {"doc_id": doc_id}。
     */
    private static final String ES_FIELD_DOC_ID = "doc_id";

    private final KnowledgeBaseCoreService knowledgeBaseCoreService;

    private final KbDocumentCoreService kbDocumentCoreService;

    private final KbTaskCoreService kbTaskCoreService;

    private final OssService ossService;

    private final ElasticsearchService elasticsearchService;

    // ==================== create ====================

    /**
     * 创建知识库，对应 RagFlow create_dataset -> KnowledgebaseService.create_with_name。
     */
    @Override
    public KnowledgeBaseBO createDataset(CreateKbBO create, String userId) {
        // 对应 Python: name 校验（非空、长度限制）
        if (create == null || !StringUtils.hasText(create.getName())) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "Dataset name can't be empty.");
        }
        String name = create.getName().trim();
        if (name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > DATASET_NAME_LIMIT) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "Dataset name length is " + name.length() + " which is large than " + DATASET_NAME_LIMIT);
        }

        // 对应 Python: duplicate_name —— 同一 tenant 下名称去重
        String finalName = duplicateName(name, userId);

        // 对应 Python: 组装 payload 并 KnowledgebaseService.save(**create_dict)
        KnowledgeBasePO po = new KnowledgeBasePO();
        po.setId(IdWorker.getId());
        po.setName(finalName);
        po.setDescription(create.getDescription());
        po.setUserId(userId);
        po.setPermission(StringUtils.hasText(create.getPermission()) ? create.getPermission() : DEFAULT_PERMISSION);
        po.setParserId(StringUtils.hasText(create.getChunkMethod()) ? create.getChunkMethod() : DEFAULT_PARSER_ID);
        po.setParserConfig(StringUtils.hasText(create.getParserConfig()) ? create.getParserConfig() : DEFAULT_PARSER_CONFIG);
        po.setDocNum(0);
        po.setTokenNum(0);
        po.setChunkNum(0);
        // creator 由 MyBatisPlus MetaObjectHandler 填充；这里显式对齐 RagFlow created_by = tenant_id
        po.setCreator(userId);

        if (!knowledgeBaseCoreService.save(po)) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Failed to save dataset");
        }

        // 对应 Python: KnowledgebaseService.get_by_id + remap_dictionary_keys
        KnowledgeBasePO saved = knowledgeBaseCoreService.getById(po.getId());
        return toBO(saved);
    }

    // ==================== list ====================

    /**
     * 分页查询知识库列表，对应 RagFlow list_datasets -> KnowledgebaseService.get_list。
     */
    @Override
    public PageResultBO<KnowledgeBaseBO> listDatasets(String name, String keywords, int page, int pageSize,
                                                      String orderby, boolean desc, String userId) {
        LambdaQueryWrapper<KnowledgeBasePO> wrapper = new LambdaQueryWrapper<>();
        // 归属过滤：仅当前用户可见（对应 RagFlow _visibility_and_status_filter 中 tenant 归属）
        wrapper.eq(KnowledgeBasePO::getUserId, userId);
        // 对应 Python: if name: where name == name
        if (StringUtils.hasText(name)) {
            wrapper.eq(KnowledgeBasePO::getName, name);
        }
        // 对应 Python: if keywords: where lower(name) contains keywords
        if (StringUtils.hasText(keywords)) {
            wrapper.like(KnowledgeBasePO::getName, keywords);
        }
        // 对应 Python: order_by(getter_by(orderby)).desc()/asc()
        applyOrder(wrapper, orderby, desc);

        Page<KnowledgeBasePO> pageParam = new Page<>(page, pageSize);
        IPage<KnowledgeBasePO> result = knowledgeBaseCoreService.page(pageParam, wrapper);

        List<KnowledgeBaseBO> data = new ArrayList<>();
        for (KnowledgeBasePO po : result.getRecords()) {
            data.add(toBO(po));
        }
        return new PageResultBO<>(data, result.getTotal());
    }

    // ==================== get ====================

    /**
     * 查询知识库详情，对应 RagFlow get_dataset。
     */
    @Override
    public KnowledgeBaseBO getDataset(Long datasetId, String userId) {
        if (datasetId == null) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "Lack of \"Dataset ID\"");
        }
        // 对应 Python: if not KnowledgebaseService.accessible(...) -> lacks permission
        KnowledgeBasePO kb = requireAccessible(datasetId, userId);
        return toBO(kb);
    }

    // ==================== 内部工具 ====================

    /**
     * 名称去重，对应 RagFlow duplicate_name：若同名存在则追加 (n)。
     */
    private String duplicateName(String name, String userId) {
        java.util.Set<String> existing = new java.util.HashSet<>();
        knowledgeBaseCoreService.list(new LambdaQueryWrapper<KnowledgeBasePO>()
                        .select(KnowledgeBasePO::getName)
                        .eq(KnowledgeBasePO::getUserId, userId))
                .forEach(k -> existing.add(k.getName()));
        if (!existing.contains(name)) {
            return name;
        }
        String candidate;
        int index = 1;
        do {
            candidate = name + "(" + index++ + ")";
        } while (existing.contains(candidate));
        return candidate;
    }

    /**
     * 排序字段映射：create_time -> created_time，update_time -> modified_time。
     * 对应 RagFlow getter_by(orderby)。
     */
    private void applyOrder(LambdaQueryWrapper<KnowledgeBasePO> wrapper, String orderby, boolean desc) {
        if ("update_time".equals(orderby) || "modified_time".equals(orderby)) {
            wrapper.orderBy(true, !desc, KnowledgeBasePO::getModifiedTime);
        } else {
            wrapper.orderBy(true, !desc, KnowledgeBasePO::getCreatedTime);
        }
    }

    /**
     * 校验知识库存在且归当前用户所有，对应 RagFlow KnowledgebaseService.accessible。
     *
     * @return 校验通过的知识库
     */
    private KnowledgeBasePO requireAccessible(Long datasetId, String userId) {
        KnowledgeBasePO kb = knowledgeBaseCoreService.getById(datasetId);
        if (kb == null) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Invalid Dataset ID");
        }
        if (!userId.equals(kb.getUserId())) {
            throw new BizException(ResCodeEnum.UNAUTHORIZED,
                    "User '" + userId + "' lacks permission for dataset '" + datasetId + "'");
        }
        return kb;
    }

    /**
     * 知识库 PO 转 BO，对应 RagFlow remap_dictionary_keys（parser_id -> chunk_method）。
     */
    private KnowledgeBaseBO toBO(KnowledgeBasePO po) {
        KnowledgeBaseBO bo = new KnowledgeBaseBO();
        bo.setId(po.getId());
        bo.setName(po.getName());
        bo.setDescription(po.getDescription());
        bo.setUserId(po.getUserId());
        bo.setPermission(po.getPermission());
        bo.setChunkMethod(po.getParserId());
        bo.setParserConfig(po.getParserConfig());
        bo.setDocNum(po.getDocNum());
        bo.setTokenNum(po.getTokenNum());
        bo.setChunkNum(po.getChunkNum());
        bo.setCreatedTime(po.getCreatedTime());
        bo.setModifiedTime(po.getModifiedTime());
        return bo;
    }

    // ==================== update ====================

    /**
     * 更新知识库，对应 RagFlow update_dataset。
     * <p>字段为 null 表示不更新（对应 Python exclude_unset）；改名时做同名校验。</p>
     */
    @Override
    public KnowledgeBaseBO updateDataset(Long datasetId, UpdateKbBO update, String userId) {
        if (update == null || (!StringUtils.hasText(update.getName())
                && update.getDescription() == null
                && !StringUtils.hasText(update.getPermission())
                && !StringUtils.hasText(update.getChunkMethod())
                && !StringUtils.hasText(update.getParserConfig()))) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "No properties were modified");
        }

        // 对应 Python: kb = get_or_none(id, tenant_id); if None -> lacks permission
        KnowledgeBasePO kb = requireAccessible(datasetId, userId);

        KnowledgeBasePO patch = new KnowledgeBasePO();
        patch.setId(kb.getId());

        // 对应 Python: if "name" in req and name.lower() != kb.name.lower(): 校验重名
        if (StringUtils.hasText(update.getName())) {
            String newName = update.getName().trim();
            if (!newName.equalsIgnoreCase(kb.getName())) {
                Long exists = knowledgeBaseCoreService.getBaseMapper().selectCount(
                        new LambdaQueryWrapper<KnowledgeBasePO>()
                                .eq(KnowledgeBasePO::getUserId, userId)
                                .eq(KnowledgeBasePO::getName, newName));
                if (exists != null && exists > 0) {
                    throw new BizException(ResCodeEnum.DATA_ERROR, "Dataset name '" + newName + "' already exists");
                }
            }
            patch.setName(newName);
        }
        if (update.getDescription() != null) {
            patch.setDescription(update.getDescription());
        }
        if (StringUtils.hasText(update.getPermission())) {
            patch.setPermission(update.getPermission());
        }
        // 对应 Python: chunk_method(parser_id) 变更；变更且未传 parser_config 时用默认配置
        if (StringUtils.hasText(update.getChunkMethod())) {
            patch.setParserId(update.getChunkMethod());
            if (!StringUtils.hasText(update.getParserConfig())
                    && !update.getChunkMethod().equals(kb.getParserId())) {
                patch.setParserConfig(DEFAULT_PARSER_CONFIG);
            }
        }
        if (StringUtils.hasText(update.getParserConfig())) {
            patch.setParserConfig(update.getParserConfig());
        }

        // 对应 Python: KnowledgebaseService.update_by_id(kb.id, req)
        if (!knowledgeBaseCoreService.updateById(patch)) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Update dataset error.(Database error)");
        }

        return toBO(knowledgeBaseCoreService.getById(kb.getId()));
    }

    // ==================== delete ====================

    /**
     * 删除知识库，对应 RagFlow delete_datasets。
     * <p>删除流程：校验归属 -> 删除其下文档（对象存储 + 文档记录 + 任务 + ES 数据）-> 删除知识库。</p>
     */
    @Override
    public int deleteDatasets(List<Long> ids, boolean deleteAll, String userId) {
        // 对应 Python: if not ids: if not delete_all -> {"success_count": 0} else 取全部
        if (CollectionUtils.isEmpty(ids)) {
            if (!deleteAll) {
                return 0;
            }
            ids = knowledgeBaseCoreService.list(new LambdaQueryWrapper<KnowledgeBasePO>()
                            .eq(KnowledgeBasePO::getUserId, userId))
                    .stream().map(KnowledgeBasePO::getId).toList();
        }

        // 对应 Python: 先整体校验归属，任一无权限直接返回错误
        List<KnowledgeBasePO> kbs = new ArrayList<>();
        for (Long id : ids) {
            kbs.add(requireAccessible(id, userId));
        }

        int successCount = 0;
        String index = DocTaskConstants.indexName(userId);
        for (KnowledgeBasePO kb : kbs) {
            Long kbId = kb.getId();
            // 对应 Python: for doc in DocumentService.query(kb_id): remove_document + 删除文件
            List<KbDocumentPO> docs = kbDocumentCoreService.list(
                    new LambdaQueryWrapper<KbDocumentPO>().eq(KbDocumentPO::getKbId, kbId));
            for (KbDocumentPO doc : docs) {
                // 删除对象存储文件
                if (StringUtils.hasText(doc.getObjectKey())) {
                    try {
                        ossService.removeObject(doc.getObjectKey());
                    } catch (Exception e) {
                        log.warn("delete dataset {}: remove object {} failed", kbId, doc.getObjectKey(), e);
                    }
                }
                // 删除文档解析任务
                kbTaskCoreService.remove(new LambdaQueryWrapper<KbTaskPO>().eq(KbTaskPO::getDocId, doc.getId()));
                // 删除 ES 中该文档分块
                try {
                    if (elasticsearchService.indexExists(index)) {
                        elasticsearchService.deleteByTerm(index, ES_FIELD_DOC_ID, String.valueOf(doc.getId()));
                    }
                } catch (Exception e) {
                    log.warn("delete dataset {}: drop ES chunks of doc {} failed", kbId, doc.getId(), e);
                }
            }
            // 删除文档记录
            kbDocumentCoreService.remove(new LambdaQueryWrapper<KbDocumentPO>().eq(KbDocumentPO::getKbId, kbId));

            // 对应 Python: KnowledgebaseService.delete_by_id(kb_id)
            if (knowledgeBaseCoreService.removeById(kbId)) {
                successCount++;
            }
        }
        return successCount;
    }

    // ==================== listDocs ====================

    /**
     * 分页查询知识库下文档列表，对应 RagFlow list_docs -> DocumentService.get_by_kb_id。
     */
    @Override
    public PageResultBO<DocItemBO> listDocs(ListDocQueryBO query, String userId) {
        // 对应 Python: if not KnowledgebaseService.accessible(...) -> You don't own the dataset
        requireAccessible(query.getKbId(), userId);

        LambdaQueryWrapper<KbDocumentPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbDocumentPO::getKbId, query.getKbId());
        // 对应 Python: if keywords: where lower(name) contains keywords
        if (StringUtils.hasText(query.getKeywords())) {
            wrapper.like(KbDocumentPO::getFileName, query.getKeywords());
        }
        // 对应 Python: if run: where run in run_status
        if (!CollectionUtils.isEmpty(query.getRun())) {
            wrapper.in(KbDocumentPO::getRun, query.getRun());
        }
        // 对应 Python: if types: where type in types
        if (!CollectionUtils.isEmpty(query.getTypes())) {
            wrapper.in(KbDocumentPO::getType, query.getTypes());
        }
        // 对应 Python: if suffix: where suffix in suffix
        if (!CollectionUtils.isEmpty(query.getSuffix())) {
            wrapper.in(KbDocumentPO::getSuffix, query.getSuffix());
        }
        // 对应 Python: order_by(getter_by(orderby)).desc()/asc()
        boolean desc = query.getDesc() == null || query.getDesc();
        if ("update_time".equals(query.getOrderby()) || "modified_time".equals(query.getOrderby())) {
            wrapper.orderBy(true, !desc, KbDocumentPO::getModifiedTime);
        } else {
            wrapper.orderBy(true, !desc, KbDocumentPO::getCreatedTime);
        }

        int page = query.getPage() == null ? 1 : query.getPage();
        int pageSize = query.getPageSize() == null ? 30 : query.getPageSize();
        Page<KbDocumentPO> pageParam = new Page<>(page, pageSize);
        IPage<KbDocumentPO> result = kbDocumentCoreService.page(pageParam, wrapper);

        List<DocItemBO> docs = new ArrayList<>();
        for (KbDocumentPO po : result.getRecords()) {
            docs.add(toDocBO(po));
        }
        return new PageResultBO<>(docs, result.getTotal());
    }

    /**
     * 文档 PO 转文档列表项 BO，对应 RagFlow map_doc_keys（parser_id -> chunk_method）。
     */
    private DocItemBO toDocBO(KbDocumentPO po) {
        DocItemBO bo = new DocItemBO();
        bo.setId(po.getId());
        bo.setName(po.getFileName());
        bo.setKbId(po.getKbId());
        bo.setChunkMethod(po.getParserId());
        bo.setParserConfig(po.getParserConfig());
        bo.setType(po.getType());
        bo.setSuffix(po.getSuffix());
        bo.setSize(po.getSize());
        bo.setChunkCount(po.getChunkCount());
        bo.setTokenCount(po.getTokenCount());
        bo.setRun(po.getRun());
        bo.setProgress(po.getProgress());
        bo.setProgressMsg(po.getProgressMsg());
        bo.setStatus(po.getStatus());
        bo.setErrorMsg(po.getErrorMsg());
        bo.setCreatedTime(po.getCreatedTime());
        bo.setModifiedTime(po.getModifiedTime());
        return bo;
    }

    // ==================== getDocFilters（type=filter 聚合） ====================

    /**
     * 文档过滤聚合，对应 RagFlow DocumentService.get_filter_by_kb_id。
     *
     * <p>按 keywords / suffix / types / run 过滤后，统计各后缀、各运行状态的文档数量。
     * 本项目暂不接入 ES/Infinity metadata，metadata 聚合仅返回 empty_metadata 计数
     * （所有命中文档均视为无 metadata），与 RagFlow 无 metadata 场景一致。</p>
     */
    @Override
    public PageResultBO<DocFilterBO> getDocFilters(ListDocQueryBO query, String userId) {
        // 对应 Python: if not KnowledgebaseService.accessible(...) -> You don't own the dataset
        requireAccessible(query.getKbId(), userId);

        // 对应 Python: query = model.select(...).where(kb_id == ...) + 各过滤条件
        LambdaQueryWrapper<KbDocumentPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KbDocumentPO::getKbId, query.getKbId());
        if (StringUtils.hasText(query.getKeywords())) {
            wrapper.like(KbDocumentPO::getFileName, query.getKeywords());
        }
        if (!CollectionUtils.isEmpty(query.getRun())) {
            wrapper.in(KbDocumentPO::getRun, query.getRun());
        }
        if (!CollectionUtils.isEmpty(query.getTypes())) {
            wrapper.in(KbDocumentPO::getType, query.getTypes());
        }
        if (!CollectionUtils.isEmpty(query.getSuffix())) {
            wrapper.in(KbDocumentPO::getSuffix, query.getSuffix());
        }

        // 对应 Python: rows = query.select(run, suffix, id)
        List<KbDocumentPO> rows = kbDocumentCoreService.list(wrapper);

        DocFilterBO filter = new DocFilterBO();
        int emptyMetadataCount = 0;
        // 对应 Python: for row in rows: suffix_counter/run_status_counter 累加
        for (KbDocumentPO row : rows) {
            String suffix = row.getSuffix() == null ? "" : row.getSuffix();
            filter.getSuffix().merge(suffix, 1, Integer::sum);
            filter.getRunStatus().merge(String.valueOf(row.getRun()), 1, Integer::sum);
            // 本项目无 metadata，全部计入 empty_metadata
            emptyMetadataCount++;
        }
        // 对应 Python: metadata_counter["empty_metadata"] = {"true": empty_metadata_count}
        Map<String, Integer> emptyMeta = new LinkedHashMap<>();
        emptyMeta.put("true", emptyMetadataCount);
        filter.getMetadata().put("empty_metadata", emptyMeta);

        // 对应 Python: return {...}, total —— 聚合结构作为单元素返回，total 为命中数
        List<DocFilterBO> data = new ArrayList<>();
        data.add(filter);
        return new PageResultBO<>(data, rows.size());
    }
}