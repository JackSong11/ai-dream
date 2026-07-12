package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.common.dto.DocTaskMessage;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.dream.common.enums.document.FileTypeEnum;
import com.example.dream.common.enums.document.ParserTypeEnum;
import com.example.dream.common.enums.document.TaskStatusEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.dal.po.KbTaskPO;
import com.example.dream.dal.po.KnowledgeBasePO;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.minio.OssService;
import com.example.dream.integration.service.redis.RedisService;
import com.example.dream.service.biz.DocumentBizService;
import com.example.dream.service.biz.bo.DocumentBO;
import com.example.dream.service.biz.bo.IngestBO;
import com.example.dream.service.core.KbDocumentCoreService;
import com.example.dream.service.core.KbTaskCoreService;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link DocumentBizService} 实现，还原 RagFlow local 上传主流程。
 *
 * <p>对应 Python：_upload_local_documents + FileService.upload_document 的核心逻辑。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentBizServiceImpl implements DocumentBizService {

    /**
     * 文件名长度限制，对应 RagFlow FILE_NAME_LEN_LIMIT = 255（字节）。
     */
    private static final int FILE_NAME_LEN_LIMIT = 255;

    /**
     * 对象存储 key 分隔符。
     */
    private static final String OBJECT_KEY_SEPARATOR = "/";

    /**
     * 解析任务默认类型，对应 RagFlow task_executor.TASK_TYPE = "common"。
     */
    private static final String TASK_TYPE_COMMON = "common";

    /**
     * 解析任务起始页码，对应 RagFlow task["from_page"] 默认值。
     */
    private static final int TASK_FROM_PAGE = 0;

    /**
     * 解析任务结束页码，-1 表示到文档末尾，对应 RagFlow task["to_page"] 默认值。
     */
    private static final int TASK_TO_PAGE = -1;

    /**
     * ES 中文档 ID 字段名，对应 RagFlow {"doc_id": doc_id}。
     */
    private static final String ES_FIELD_DOC_ID = "doc_id";

    /**
     * JSON 序列化/反序列化工具，用于 parser_config 字段级合并。
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KbDocumentCoreService kbDocumentCoreService;

    private final KbTaskCoreService kbTaskCoreService;

    private final KnowledgeBaseCoreService knowledgeBaseCoreService;

    private final ElasticsearchService elasticsearchService;

    private final OssService ossService;

    private final RedisService redisService;

    @Override
    public List<DocumentBO> uploadLocalDocuments(Long kbId,
                                                 List<MultipartFile> files,
                                                 String userId) {
        // 对应 Python: if "file" not in files -> No file part!
        if (CollectionUtils.isEmpty(files)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "请选择要上传的文件");
        }

        // 对应 Python: 遍历校验每个文件（无文件名 / 文件名过长）
        files.forEach(this::validateFile);

        // 对应 Python: FileService.upload_document —— 逐个文件上传并入库
        List<DocumentBO> result = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            result.add(uploadSingle(kbId, file, userId));
        }
        return result;
    }

    /**
     * 校验单个文件：文件名非空、长度不超限。
     * 对应 RagFlow _upload_local_documents 中的文件遍历校验。
     *
     * @param file 上传文件
     */
    private void validateFile(MultipartFile file) {
        String filename = file == null ? null : file.getOriginalFilename();
        if (!StringUtils.hasText(filename)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "存在未选择的文件");
        }
        if (filename.getBytes(StandardCharsets.UTF_8).length > FILE_NAME_LEN_LIMIT) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "文件名长度不能超过 " + FILE_NAME_LEN_LIMIT + " 字节");
        }
    }

    /**
     * 单文件上传：文件名去重 -> 类型判断 -> parser 映射 -> 存储 -> 入库。
     * 对应 RagFlow FileService.upload_document 中对单个文件的处理逻辑。
     *
     * @param kbId   数据集 ID
     * @param file   上传文件
     * @param userId 当前用户 ID
     * @return 文档业务对象
     */
    private DocumentBO uploadSingle(Long kbId, MultipartFile file, String userId) {
        // 对应 Python: duplicate_name —— 同一 kb 下文件名去重
        String filename = duplicateName(kbId, file.getOriginalFilename());

        // 对应 Python: filename_type —— 依据后缀判断文档类型
        FileTypeEnum fileType = FileTypeEnum.ofFilename(filename);
        if (fileType == FileTypeEnum.OTHER) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "暂不支持该类型的文件");
        }

        // 对应 Python: parser_id 依据类型 / 后缀做映射
        ParserTypeEnum parserType = ParserTypeEnum.resolve(fileType, filename);

        // 对应 Python: STORAGE_IMPL.put —— objectKey 采用 UUID，天然唯一，无需查重
        String objectKey = storeObject(kbId, filename, file, userId);

        // 对应 Python: doc = {...}; DocumentService.insert(doc)
        KbDocumentPO doc = buildDocument(kbId, filename, fileType, parserType, objectKey, file.getSize());
        kbDocumentCoreService.save(doc);

        return toBO(doc);
    }

    /**
     * 将文件写入对象存储，返回最终 objectKey。
     * <p>主流做法：objectKey 使用唯一 ID（UUID）而非原始文件名，从根本上规避命名冲突，
     * 也避免中文/特殊字符导致的 key 不合法问题；原始文件名仅落库用于展示。</p>
     *
     * @param kbId     数据集 ID
     * @param filename 原始文件名（用于取后缀）
     * @param file     上传文件
     * @return 对象存储 key，形如 {datasetId}/{uuid}.{suffix}
     */
    private String storeObject(Long kbId, String filename, MultipartFile file, String userId) {
        String suffix = FileTypeEnum.resolveSuffix(filename);
        String objectName = UUID.randomUUID().toString().replace("-", "");
        if (!suffix.isEmpty()) {
            objectName = objectName + "." + suffix;
        }
        String objectKey = userId + OBJECT_KEY_SEPARATOR + kbId + OBJECT_KEY_SEPARATOR + objectName;
        try (InputStream in = file.getInputStream()) {
            ossService.putObject(objectKey, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "文件上传失败: " + filename);
        }
        return objectKey;
    }

    /**
     * 构建待入库的文档持久化对象。
     */
    private KbDocumentPO buildDocument(Long kbId, String filename, FileTypeEnum fileType,
                                       ParserTypeEnum parserType, String objectKey, long size) {
        KbDocumentPO doc = new KbDocumentPO();
        doc.setId(IdWorker.getId());
        doc.setKbId(kbId);
        doc.setParserId(parserType.getCode());
        doc.setType(fileType.getCode());
        doc.setFileName(filename);
        doc.setSuffix(FileTypeEnum.resolveSuffix(filename));
        doc.setObjectKey(objectKey);
        doc.setSize(size);
        doc.setRun(TaskStatusEnum.UNSTART.getValue());
        doc.setChunkCount(0);
        doc.setTokenCount(0);
        return doc;
    }

    /**
     * 同一 dataset 下文件名去重：若已存在同名文档，追加 (n) 后缀。
     * 对应 RagFlow duplicate_name。
     * <p>优化：一次性查出该 dataset 下已占用的文件名集合，在内存中计算可用名称，
     * 避免原「循环多次 count 查库」的多次数据库往返。</p>
     *
     * @param kbId 数据集 ID
     * @param name 原始文件名
     * @return 去重后的文件名
     */
    private String duplicateName(Long kbId, String name) {
        // 一次查询取出所有已存在文件名
        Set<String> existingNames = kbDocumentCoreService.list(new LambdaQueryWrapper<KbDocumentPO>()
                        .select(KbDocumentPO::getFileName)
                        .eq(KbDocumentPO::getKbId, kbId))
                .stream()
                .map(KbDocumentPO::getFileName)
                .collect(Collectors.toSet());

        if (!existingNames.contains(name)) {
            return name;
        }
        String candidate;
        int index = 1;
        do {
            candidate = appendIndex(name, index++);
        } while (existingNames.contains(candidate));
        return candidate;
    }

    /**
     * 在文件名后缀前追加序号，如 a.txt -> a(1).txt。
     */
    private String appendIndex(String name, int index) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            return name.substring(0, dot) + "(" + index + ")" + name.substring(dot);
        }
        return name + "(" + index + ")";
    }

    /**
     * PO 转 BO。
     */
    private DocumentBO toBO(KbDocumentPO doc) {
        DocumentBO bo = new DocumentBO();
        bo.setId(doc.getId());
        bo.setName(doc.getFileName());
        bo.setKbId(doc.getKbId());
        bo.setChunkMethod(doc.getParserId());
        bo.setChunkCount(doc.getChunkCount());
        bo.setTokenCount(doc.getTokenCount());
        bo.setType(doc.getType());
        bo.setSuffix(doc.getSuffix());
        bo.setSize(doc.getSize());
        bo.setLocation(doc.getObjectKey());
        bo.setRun(doc.getRun());
        return bo;
    }

    // ==================== ingest / 解析主流程 ====================

    /**
     * 触发文档解析 / 运行状态变更，对应 RagFlow ingest + _run_sync。
     * <p>与 Python 版保持一致：先整体鉴权，再逐个文档处理状态变更、任务取消、
     * 数据清理与解析触发；任一步骤失败即抛出业务异常（对应 Python 返回错误码）。</p>
     */
    @Override
    public void ingest(IngestBO ingest, String userId) {
        List<Long> docIds = ingest.getDocIds();
        if (CollectionUtils.isEmpty(docIds)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "doc_ids 不能为空");
        }

        // 对应 Python: for doc_id in req["doc_ids"]: if not DocumentService.accessible(...) -> No authorization.
        for (Long docId : docIds) {
            if (!accessible(docId, userId)) {
                throw new BizException(ResCodeEnum.UNAUTHORIZED, "No authorization.");
            }
        }

        // 对应 Python: for doc_id in req["doc_ids"]: 逐个处理
        for (Long docId : docIds) {
            handleSingleDoc(docId, ingest, userId);
        }
    }

    /**
     * 处理单个文档的运行状态变更，对应 RagFlow _run_sync 循环体。
     */
    private void handleSingleDoc(Long docId, IngestBO ingest, String userId) {
        int run = ingest.getRun();
        // 对应 Python: rerun_with_delete = str(req["run"]) == RUNNING and req.get("delete")
        boolean rerunWithDelete = TaskStatusEnum.RUNNING.getValue() == run && ingest.isDelete();

        // 对应 Python: e, doc = DocumentService.get_by_id(doc_id)；Document not found!
        // 主键统一为 Long，docId 入参为字符串形式，查询前转 Long
        KbDocumentPO doc = kbDocumentCoreService.getById(docId);
        if (doc == null) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Document not found!");
        }

        // 对应 Python: info = {"run": str(req["run"]), "progress": 0}
        KbDocumentPO info = new KbDocumentPO();
        info.setId(doc.getId());
        info.setRun(run);
        if (rerunWithDelete) {
            // 对应 Python: info["progress_msg"]=""; info["chunk_num"]=0; info["token_num"]=0
            info.setChunkCount(0);
            info.setTokenCount(0);
        }

        // 对应 Python: if str(req["run"]) == CANCEL: ...
        if (TaskStatusEnum.CANCEL.getValue() == run) {
            List<KbTaskPO> tasks = kbTaskCoreService.list(new LambdaQueryWrapper<KbTaskPO>()
                    .eq(KbTaskPO::getDocId, docId));
            boolean hasUnfinishedTask = tasks.stream()
                    .anyMatch(t -> t.getProgress() == null || t.getProgress().compareTo(BigDecimal.ONE) < 0);
            boolean runningOrCancel = TaskStatusEnum.RUNNING.getValue() == doc.getRun()
                    || TaskStatusEnum.CANCEL.getValue() == doc.getRun();
            if (runningOrCancel || hasUnfinishedTask) {
                // 对应 Python: cancel_all_task_of(doc_id)
                cancelAllTaskOf(docId);
            } else {
                throw new BizException(ResCodeEnum.DATA_ERROR, "Cannot cancel a task that is not in RUNNING status");
            }
        }

        // 对应 Python: if all([rerun_with_delete, str(doc.run)==DONE]): clear_chunk_num_when_rerun(doc_id)
        if (rerunWithDelete && TaskStatusEnum.DONE.getValue() == doc.getRun()) {
            clearChunkNumWhenRerun(docId);
        }

        // 对应 Python: DocumentService.update_by_id(doc_id, info)
        kbDocumentCoreService.updateById(info);

        // 对应 Python: if req.get("delete"): TaskService.filter_delete + docStoreConn.delete
        if (ingest.isDelete()) {
            kbTaskCoreService.remove(new LambdaQueryWrapper<KbTaskPO>().eq(KbTaskPO::getDocId, docId));
            String index = DocTaskConstants.indexName(userId);
            if (elasticsearchService.indexExists(index)) {
                elasticsearchService.deleteByTerm(index, ES_FIELD_DOC_ID, String.valueOf(docId));
            }
        }

        // 对应 Python: if str(req["run"]) == RUNNING: apply_kb + DocumentService.run(...)
        if (TaskStatusEnum.RUNNING.getValue() == run) {
            if (ingest.isApplyKb()) {
                applyKbParserConfig(doc);
            }
            runDocument(userId, doc);
        }
    }

    /**
     * 文档鉴权，对应 RagFlow DocumentService.accessible(doc_id, user_id)。
     * <p>校验文档存在且其所属知识库归当前用户所有。</p>
     */
    private boolean accessible(Long docId, String userId) {
        KbDocumentPO doc = kbDocumentCoreService.getById(docId);
        if (doc == null) {
            return false;
        }
        KnowledgeBasePO kb = knowledgeBaseCoreService.getById(doc.getKbId());
        if (kb == null) {
            return false;
        }
        // 归属校验：知识库 tenant 或创建人与当前用户一致
        return userId.equals(kb.getCreator());
    }

    /**
     * 取消文档对应的全部解析任务，对应 RagFlow cancel_all_task_of(doc_id)。
     * <p>将该文档下所有任务进度标记为已取消（-1 语义此处用 1 结束），并删除队列任务。
     * 这里简化为直接删除任务记录，语义等同于终止解析流程。</p>
     */
    private void cancelAllTaskOf(Long docId) {
        kbTaskCoreService.remove(new LambdaQueryWrapper<KbTaskPO>().eq(KbTaskPO::getDocId, docId));
    }

    /**
     * 重跑前清理文档分块统计，对应 RagFlow DocumentService.clear_chunk_num_when_rerun(doc_id)。
     * <p>严格对齐 Python：仅清零 chunk/token 统计，progress / progress_msg 由随后的
     * update_by_id(doc_id, info) 统一设置，此处不再重复设置以避免语义重叠。</p>
     */
    private void clearChunkNumWhenRerun(Long docId) {
        KbDocumentPO update = new KbDocumentPO();
        update.setId(docId);
        update.setChunkCount(0);
        update.setTokenCount(0);
        kbDocumentCoreService.updateById(update);
    }

    /**
     * 应用知识库解析配置到文档，对应 RagFlow apply_kb 分支。
     * <p>严格对齐 Python：仅将 kb.parser_config 的 llm_id / enable_metadata / metadata
     * 三个字段合并进 doc 原有的 parser_config（其余字段保留），而非整体覆盖。</p>
     * <pre>
     * doc.parser_config["llm_id"]          = kb.parser_config.get("llm_id")
     * doc.parser_config["enable_metadata"] = kb.parser_config.get("enable_metadata", False)
     * doc.parser_config["metadata"]        = kb.parser_config.get("metadata", {})
     * </pre>
     */
    private void applyKbParserConfig(KbDocumentPO doc) {
        KnowledgeBasePO kb = knowledgeBaseCoreService.getById(doc.getKbId());
        if (kb == null) {
            throw new BizException(ResCodeEnum.DATA_ERROR, "Can't find this dataset!");
        }

        try {
            // 解析 doc 原有 parser_config（为空则视为空对象，保留其余字段）
            ObjectNode docConfig = readAsObjectNode(doc.getParserConfig());

            // 这里有一段把 kb.getParserConfig() 里面一些字段，set到docConfig，我删了

            String merged = OBJECT_MAPPER.writeValueAsString(docConfig);
            KbDocumentPO update = new KbDocumentPO();
            update.setId(doc.getId());
            update.setParserConfig(merged);
            kbDocumentCoreService.updateById(update);

            // 关键：同步刷新内存 doc，确保随后 runDocument 投递的任务消息携带最新 parser_config
            // （对应 Python：apply_kb 分支后 doc.to_dict() 已是更新后的配置）
            doc.setParserConfig(merged);
        } catch (IOException e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "解析 parser_config 失败");
        }
    }

    /**
     * 将 JSON 字符串解析为可写的 ObjectNode；空字符串或非对象时返回空对象节点。
     */
    private ObjectNode readAsObjectNode(String json) throws IOException {
        if (!StringUtils.hasText(json)) {
            return OBJECT_MAPPER.createObjectNode();
        }
        JsonNode node = OBJECT_MAPPER.readTree(json);
        return node instanceof ObjectNode objectNode ? objectNode : OBJECT_MAPPER.createObjectNode();
    }

    /**
     * 触发文档解析，对应 RagFlow queue_tasks 全流程。
     * <p>生产端职责：拆分任务 -> 计算指纹 -> 复用旧任务分块 -> 清理旧任务/旧 ES chunks
     * -> 回填 chunk_num -> 批量入库 -> 仅投递未完成任务到 Redis Stream。
     * 真正的分块 / 向量化 / 写 ES 由 dream-processor 异步消费执行。</p>
     */
    private void runDocument(String userId, KbDocumentPO doc) {
        // 1) 拆分任务（当前架构统一单任务，对应 queue_tasks 的 else 分支）
        List<KbTaskPO> parseTaskArray = new ArrayList<>();
        KbTaskPO task = new KbTaskPO();
        task.setId(IdWorker.getId());
        task.setDocId(doc.getId());
        task.setFromPage(TASK_FROM_PAGE);
        task.setToPage(TASK_TO_PAGE);
        task.setTaskType(TASK_TYPE_COMMON);
        task.setProgress(BigDecimal.ZERO);
        task.setProgressMsg("");
        task.setRetryCount(0);
        parseTaskArray.add(task);

        // 2) 计算每个任务的 digest（对应 queue_tasks 中 xxhash 指纹，用 SHA-256 替代）
        for (KbTaskPO t : parseTaskArray) {
            t.setDigest(computeDigest(doc, t));
            t.setProgress(BigDecimal.ZERO);
        }

        // 3) 复用上次任务的分块（对应 reuse_prev_task_chunks）
        List<KbTaskPO> prevTasks = kbTaskCoreService.list(new LambdaQueryWrapper<KbTaskPO>()
                .eq(KbTaskPO::getDocId, doc.getId()));
        int ckNum = 0;
        if (!CollectionUtils.isEmpty(prevTasks)) {
            for (KbTaskPO t : parseTaskArray) {
                ckNum += reusePrevTaskChunks(t, prevTasks);
            }
            // 删除旧任务记录（对应 TaskService.filter_delete）
            kbTaskCoreService.remove(new LambdaQueryWrapper<KbTaskPO>()
                    .eq(KbTaskPO::getDocId, doc.getId()));
            // 删除旧任务已产生的 ES 分块（对应 docStoreConn.delete）
            List<String> preChunkIds = new ArrayList<>();
            for (KbTaskPO pre : prevTasks) {
                if (StringUtils.hasText(pre.getChunkIds())) {
                    for (String cid : pre.getChunkIds().split("\\s+")) {
                        if (StringUtils.hasText(cid)) {
                            preChunkIds.add(cid);
                        }
                    }
                }
            }
            if (!preChunkIds.isEmpty()) {
                String index = DocTaskConstants.indexName(userId);
                if (elasticsearchService.indexExists(index)) {
                    for (String chunkId : preChunkIds) {
                        elasticsearchService.deleteDocument(index, chunkId);
                    }
                }
            }
        }

        // 4) 回填文档分块数（对应 DocumentService.update_by_id(doc_id, {"chunk_num": ck_num})）
        KbDocumentPO chunkNumUpdate = new KbDocumentPO();
        chunkNumUpdate.setId(doc.getId());
        chunkNumUpdate.setChunkCount(ckNum);
        kbDocumentCoreService.updateById(chunkNumUpdate);

        // 5) 批量入库解析任务（对应 bulk_insert_into_db(Task, parse_task_array)）
        kbTaskCoreService.saveBatch(parseTaskArray);

        // 6) 仅投递未完成任务（对应 unfinished_task_array = progress < 1.0）
        List<KbTaskPO> unfinishedTasks = parseTaskArray.stream()
                .filter(t -> t.getProgress() == null || t.getProgress().compareTo(BigDecimal.ONE) < 0)
                .toList();

        for (KbTaskPO unfinished : unfinishedTasks) {
            DocTaskMessage message = getDocTaskMessage(userId, doc, unfinished.getId());
            try {
                String payload = OBJECT_MAPPER.writeValueAsString(message);
                String recordId = redisService.streamAdd(
                        DocTaskConstants.SVR_QUEUE,
                        Map.of(DocTaskConstants.MSG_FIELD_PAYLOAD, payload));
                if (recordId == null) {
                    throw new BizException(ResCodeEnum.SERVER_ERROR, "任务消息投递失败");
                }
                log.info("文档解析任务已投递, docId={}, taskId={}, recordId={}",
                        doc.getId(), unfinished.getId(), recordId);
            } catch (JsonProcessingException e) {
                throw new BizException(ResCodeEnum.SERVER_ERROR, "任务消息序列化失败");
            }
        }
    }

    /**
     * 复用上次任务的分块，对应 RagFlow reuse_prev_task_chunks。
     * <p>按 from_page + digest 匹配旧任务，命中且已完成（progress=1）且有 chunk_ids 时复用，
     * 返回复用的分块数量；否则返回 0。</p>
     */
    private int reusePrevTaskChunks(KbTaskPO task, List<KbTaskPO> prevTasks) {
        KbTaskPO matched = null;
        for (KbTaskPO prev : prevTasks) {
            int prevFrom = prev.getFromPage() == null ? 0 : prev.getFromPage();
            int curFrom = task.getFromPage() == null ? 0 : task.getFromPage();
            if (prevFrom == curFrom
                    && task.getDigest() != null
                    && task.getDigest().equals(prev.getDigest())) {
                matched = prev;
                break;
            }
        }
        if (matched == null) {
            return 0;
        }
        boolean done = matched.getProgress() != null
                && matched.getProgress().compareTo(BigDecimal.ONE) >= 0;
        if (!done || !StringUtils.hasText(matched.getChunkIds())) {
            return 0;
        }
        task.setChunkIds(matched.getChunkIds());
        task.setProgress(BigDecimal.ONE);
        task.setProgressMsg("Reused previous task's chunks.");
        int reused = task.getChunkIds().split("\\s+").length;
        // 置空旧任务的 chunk_ids，避免随后删除旧 ES chunks 时误删已复用的分块
        matched.setChunkIds("");
        return reused;
    }

    /**
     * 计算任务指纹，对应 RagFlow queue_tasks 中的 xxhash 指纹。
     * <p>以文档 parser 配置 + doc_id/from_page/to_page 作为输入，用 SHA-256 生成指纹，
     * 避免引入 xxhash 第三方依赖。</p>
     */
    private String computeDigest(KbDocumentPO doc, KbTaskPO task) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(String.valueOf(doc.getParserId()).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(doc.getParserConfig()).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(task.getDocId()).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(task.getFromPage()).getBytes(StandardCharsets.UTF_8));
            md.update(String.valueOf(task.getToPage()).getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "任务指纹计算失败");
        }
    }

    @NotNull
    private static DocTaskMessage getDocTaskMessage(String userId, KbDocumentPO doc, Long taskId) {
        DocTaskMessage message = new DocTaskMessage();
        message.setTaskId(taskId);
        message.setDocId(doc.getId());
        message.setKbId(doc.getKbId());
        message.setUserId(userId);
        message.setParserId(doc.getParserId());
        message.setParserConfig(doc.getParserConfig());
        message.setName(doc.getFileName());
        message.setLocation(doc.getObjectKey());
        message.setFromPage(TASK_FROM_PAGE);
        message.setToPage(TASK_TO_PAGE);
        message.setSize(doc.getSize());
        return message;
    }
}