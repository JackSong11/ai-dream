package com.example.dream.processor.doc;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.common.dto.DocTaskMessage;
import com.example.dream.common.enums.document.TaskStatusEnum;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.dal.po.KbTaskPO;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.minio.OssService;
import com.example.dream.service.core.KbDocumentCoreService;
import com.example.dream.service.core.KbTaskCoreService;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.dream.dal.po.KnowledgeBasePO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档解析任务处理器（消费端核心编排）。
 *
 * <p>严格对齐 RagFlow task_executor.do_handle_task 的处理链路：
 * <ol>
 *   <li>{@code initKb}：绑定嵌入模型、探测向量维度，创建带 dense_vector 的 ES 索引</li>
 *   <li>{@code buildChunks}：从 MinIO 拉取文件二进制，用 Tika 提取纯文本并按 token 分块</li>
 *   <li>{@code embedding}：调嵌入模型为每个分块生成向量，挂到 q_{dim}_vec 字段</li>
 *   <li>{@code insertChunks}：批量写入 ES，回写文档 chunk/token 统计与 run/progress</li>
 * </ol>
 * </p>
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentTaskHandler {

    /**
     * 单个分块的目标 token 数（近似，用字符数估算），对应 RagFlow chunk_token_num 默认 128。
     */
    private static final int CHUNK_TOKEN_NUM = 128;

    /**
     * token 到字符的粗略换算系数（中英文混合经验值：1 token ≈ 2 字符）。
     */
    private static final int CHARS_PER_TOKEN = 2;

    /**
     * 写 ES 的批大小，对应 RagFlow DOC_BULK_SIZE。
     */
    private static final int DOC_BULK_SIZE = 64;

    /**
     * 文档最大字节数，对应 RagFlow DOC_MAXIMUM_SIZE（128MB）。
     */
    private static final long DOC_MAXIMUM_SIZE = 128L * 1024 * 1024;

    /**
     * 嵌入模型返回空输入时的占位内容，对应 RagFlow 空文本占位。
     */
    private static final String EMPTY_CONTENT_PLACEHOLDER = "None";

    /**
     * ES 向量字段名前缀，与维度拼接为 q_{dim}_vec，对应 RagFlow q_%d_vec。
     */
    private static final String VECTOR_FIELD_PREFIX = "q_";

    /**
     * ES 向量字段名后缀。
     */
    private static final String VECTOR_FIELD_SUFFIX = "_vec";

    // ES chunk 文档字段名，对应 RagFlow chunk 字段定义
    private static final String FIELD_ID = "id";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_KB_ID = "kb_id";
    private static final String FIELD_DOC_NAME = "docnm_kwd";
    private static final String FIELD_CONTENT = "content_with_weight";
    private static final String FIELD_CONTENT_LTKS = "content_ltks";
    private static final String FIELD_AVAILABLE = "available_int";
    private static final String FIELD_POSITION = "position_int";
    private static final String FIELD_CREATE_TIME = "create_time";
    private static final String FIELD_CREATE_TS = "create_timestamp_flt";

    // 各阶段进度值，对应 RagFlow set_progress 各步骤的进度上报
    private static final BigDecimal PROGRESS_INIT_KB = new BigDecimal("0.1");
    private static final BigDecimal PROGRESS_TEXT_EXTRACTED = new BigDecimal("0.4");
    private static final BigDecimal PROGRESS_CHUNKED = new BigDecimal("0.6");
    private static final BigDecimal PROGRESS_EMBEDDED = new BigDecimal("0.8");
    private static final BigDecimal PROGRESS_FAIL = new BigDecimal("-1");

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OssService ossService;

    private final EmbeddingModel embeddingModel;

    private final ElasticsearchService elasticsearchService;

    private final KbDocumentCoreService kbDocumentCoreService;

    private final KbTaskCoreService kbTaskCoreService;

    private final KnowledgeBaseCoreService knowledgeBaseCoreService;

    private final Tika tika = new Tika();

    /**
     * 处理一条文档解析任务的完整链路。
     *
     * @param msg 任务消息
     */
    public void handle(DocTaskMessage msg) {
        try {
            // 文件大小超限校验（对应 RagFlow DOC_MAXIMUM_SIZE 检查）
            if (msg.getSize() != null && msg.getSize() > DOC_MAXIMUM_SIZE) {
                failDoc(msg, "文件超过大小上限(<= 128MB)");
                return;
            }

            // 1) initKb：绑定嵌入模型 + 探测维度 + 建索引
            int vectorSize = initKb(msg);

            // 2) buildChunks：拉文件 -> Tika 提取文本 -> 分块
            List<Map<String, Object>> chunks = buildChunks(msg);
            if (chunks.isEmpty()) {
                // 无内容：直接标记完成（对应 RagFlow "No chunk built"）
                markProgress(msg, BigDecimal.ONE, "未从文档中解析出内容");
                finishDoc(msg, 0, 0);
                return;
            }

            // 3) embedding：生成向量并挂 q_{dim}_vec
            int tokenCount = embedding(msg, chunks, vectorSize);

            // 4) insertChunks：批量写 ES + 回写统计
            insertChunks(msg, chunks);

            finishDoc(msg, chunks.size(), tokenCount);
            markProgress(msg, BigDecimal.ONE, "解析完成，共 " + chunks.size() + " 个分块");
        } catch (RuntimeException e) {
            failDoc(msg, "解析失败: " + e.getMessage());
            // 保留原始异常类型与堆栈，交由上层（消费者）记录日志，不做无意义包装
            throw e;
        } catch (Exception e) {
            failDoc(msg, "解析失败: " + e.getMessage());
            throw new IllegalStateException("文档解析任务处理失败, docId=" + msg.getDocId(), e);
        }
    }

    /**
     * initKb：绑定嵌入模型，探测向量维度并创建 ES 索引。
     * 对应 RagFlow do_handle_task 中 embedding_model.encode(["ok"]) + init_kb。
     *
     * @return 向量维度
     */
    private int initKb(DocTaskMessage msg) {
        // 探测向量维度（对应 RagFlow: vts,_ = embedding_model.encode(["ok"]); vector_size=len(vts[0])）
        float[] probe = embeddingModel.embed("ok");
        int vectorSize = probe.length;

        // 创建带 q_{dim}_vec(dense_vector) 的索引（对应 RagFlow init_kb -> create_idx）
        String index = DocTaskConstants.indexName(msg.getTenantId());
        elasticsearchService.createChunkIndexIfAbsent(index, vectorSize);

        markProgress(msg, PROGRESS_INIT_KB, "已绑定嵌入模型(维度=" + vectorSize + ")，索引就绪");
        return vectorSize;
    }

    /**
     * buildChunks：从 MinIO 拉取文件二进制，用 Tika 提取纯文本，再按 token 近似分块。
     * 对应 RagFlow build_chunks（此处第一版仅做纯文本分块，不含图片/OCR/关键词等增强）。
     *
     * @return 分块列表，每个分块为一个 ES 文档字段 Map
     */
    private List<Map<String, Object>> buildChunks(DocTaskMessage msg) throws Exception {
        String text;
        // 从对象存储读取文件（location 为完整 objectKey，使用默认桶）
        try (InputStream in = ossService.getObject(msg.getLocation())) {
            // Tika 自动识别格式（PDF/Word/PPT/txt/md 等）并提取纯文本
            text = tika.parseToString(in);
        }
        markProgress(msg, PROGRESS_TEXT_EXTRACTED, "文档文本提取完成");

        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<String> segments = splitByToken(text);
        List<Map<String, Object>> chunks = new ArrayList<>(segments.size());
        String createTime = LocalDateTime.now().format(TIME_FMT);
        double createTs = System.currentTimeMillis() / 1000.0;

        int position = 0;
        for (String seg : segments) {
            String content = seg.trim();
            if (content.isEmpty()) {
                continue;
            }
            Map<String, Object> ck = new LinkedHashMap<>();
            // chunk id：内容 + docId 的哈希，保证同内容幂等（对应 RagFlow xxhash 语义，此处用 hashCode 组合）
            ck.put(FIELD_ID, chunkId(content, msg.getDocId(), position));
            ck.put(FIELD_DOC_ID, msg.getDocId());
            ck.put(FIELD_KB_ID, msg.getKbId());
            ck.put(FIELD_DOC_NAME, msg.getName());
            ck.put(FIELD_CONTENT, content);
            ck.put(FIELD_CONTENT_LTKS, content);
            ck.put(FIELD_AVAILABLE, 1);
            ck.put(FIELD_POSITION, position);
            ck.put(FIELD_CREATE_TIME, createTime);
            ck.put(FIELD_CREATE_TS, createTs);
            chunks.add(ck);
            position++;
        }
        markProgress(msg, PROGRESS_CHUNKED, "分块完成，共 " + chunks.size() + " 块");
        return chunks;
    }

    /**
     * 按 token 近似切分文本：以字符窗口滑动，尽量在段落/句子边界断开。
     */
    private List<String> splitByToken(String text) {
        int windowChars = CHUNK_TOKEN_NUM * CHARS_PER_TOKEN;
        List<String> result = new ArrayList<>();
        // 先按空行/换行归并，再按窗口切
        String normalized = text.replaceAll("\\r\\n", "\n").replaceAll("\\n{2,}", "\n");
        int len = normalized.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + windowChars, len);
            // 尝试在窗口末尾附近的句子边界断开，避免切断句子
            if (end < len) {
                int boundary = findBoundary(normalized, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            result.add(normalized.substring(start, end));
            start = end;
        }
        return result;
    }

    /**
     * 在 [start, end) 范围内从后向前寻找句子/标点边界，找不到则返回 end。
     */
    private int findBoundary(String text, int start, int end) {
        String delimiters = "。！？；\n.!?;";
        for (int i = end - 1; i > start; i--) {
            if (delimiters.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }

    /**
     * 生成分块 ID：内容 + docId + 位置 的稳定哈希（十六进制）。
     */
    private String chunkId(String content, String docId, int position) {
        long h = 1125899906842597L;
        String base = content + "|" + docId + "|" + position;
        for (int i = 0; i < base.length(); i++) {
            h = 31 * h + base.charAt(i);
        }
        return Long.toHexString(h);
    }

    /**
     * embedding：为每个分块生成向量并挂到 q_{dim}_vec 字段。
     * 对应 RagFlow embedding()：批量编码 content_with_weight，向量以 q_%d_vec 命名。
     *
     * @return 估算的 token 总数
     */
    private int embedding(DocTaskMessage msg, List<Map<String, Object>> chunks, int vectorSize) {
        String vectorField = VECTOR_FIELD_PREFIX + vectorSize + VECTOR_FIELD_SUFFIX;
        // 取每个分块的正文用于向量化（对应 RagFlow 以 content_with_weight 为主）
        List<String> contents = new ArrayList<>(chunks.size());
        for (Map<String, Object> ck : chunks) {
            String c = String.valueOf(ck.get(FIELD_CONTENT));
            // 空白内容用占位符，避免嵌入模型收到空输入（对应 RagFlow "None" 占位）
            contents.add(c == null || c.isBlank() ? EMPTY_CONTENT_PLACEHOLDER : c);
        }

        // 批量生成向量（Spring AI 支持一次传入多文本）
        List<float[]> vectors = embeddingModel.embed(contents);
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "向量数量与分块数量不一致: " + vectors.size() + " != " + chunks.size());
        }

        int tokenCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] vec = vectors.get(i);
            // ES dense_vector 接受 float 列表
            List<Float> vecList = new ArrayList<>(vec.length);
            for (float v : vec) {
                vecList.add(v);
            }
            chunks.get(i).put(vectorField, vecList);
            // token 数按字符粗略估算（对应 RagFlow num_tokens，此处近似）
            tokenCount += contents.get(i).length() / CHARS_PER_TOKEN + 1;
        }

        markProgress(msg, PROGRESS_EMBEDDED, "向量化完成");
        return tokenCount;
    }

    /**
     * insertChunks：按 DOC_BULK_SIZE 批量写入 ES，并回填 task 的 chunk_ids。
     * 对应 RagFlow insert_chunks() -> docStoreConn.insert + TaskService.update_chunk_ids。
     */
    private void insertChunks(DocTaskMessage msg, List<Map<String, Object>> chunks) {
        String index = DocTaskConstants.indexName(msg.getTenantId());
        List<String> allChunkIds = new ArrayList<>(chunks.size());

        for (int b = 0; b < chunks.size(); b += DOC_BULK_SIZE) {
            int end = Math.min(b + DOC_BULK_SIZE, chunks.size());
            List<Map<String, Object>> batch = chunks.subList(b, end);

            // 以 chunk 的 id 为 ES 文档 ID，批量写入
            Map<String, Map<String, Object>> idDocuments = new LinkedHashMap<>();
            for (Map<String, Object> ck : batch) {
                String id = String.valueOf(ck.get(FIELD_ID));
                idDocuments.put(id, ck);
                allChunkIds.add(id);
            }
            boolean ok = elasticsearchService.bulkSave(index, idDocuments);
            if (!ok) {
                throw new IllegalStateException("写入 ES 失败, index=" + index + ", batchStart=" + b);
            }
            log.info("写入分块到 ES, index={}, batch=[{},{}), total={}", index, b, end, chunks.size());
        }

        // 回填 task.chunk_ids（对应 RagFlow update_chunk_ids，空格分隔）
        KbTaskPO taskUpdate = new KbTaskPO();
        taskUpdate.setId(Long.valueOf(msg.getTaskId()));
        taskUpdate.setChunkIds(String.join(" ", allChunkIds));
        kbTaskCoreService.updateById(taskUpdate);
    }

    /**
     * 回写任务进度（对应 RagFlow set_progress -> TaskService.update_progress）。
     * <p>同步更新 task 与 document 的 progress / progress_msg，便于前端轮询展示。</p>
     */
    private void markProgress(DocTaskMessage msg, BigDecimal progress, String message) {
        KbTaskPO task = new KbTaskPO();
        task.setId(Long.valueOf(msg.getTaskId()));
        task.setProgress(progress);
        task.setProgressMsg(message);
        kbTaskCoreService.updateById(task);

        KbDocumentPO doc = new KbDocumentPO();
        doc.setId(Long.valueOf(msg.getDocId()));
        doc.setProgress(progress);
        doc.setProgressMsg(message);
        kbDocumentCoreService.updateById(doc);
    }

    /**
     * 解析成功后回写文档统计与状态（对应 RagFlow increment_chunk_num + run=DONE + progress=1）。
     * <p>严格对齐 Python increment_chunk_num：chunk/token 采用<strong>原子累加</strong>语义，
     * 并同步累加所属知识库的 chunk_num / token_num（Python 在同一事务里同时更新 document 与 knowledgebase）。
     * ingest 重跑前已清零文档统计，因此累加结果即为最终值，且天然支持一个文档拆分为多个 task 的场景。</p>
     */
    private void finishDoc(DocTaskMessage msg, int chunkCount, int tokenCount) {
        Long docId = Long.valueOf(msg.getDocId());
        // 文档：run=DONE、progress=1，chunk/token 原子累加（对应 cls.model.chunk_num + chunk_num）
        LambdaUpdateWrapper<KbDocumentPO> docUpdate = new LambdaUpdateWrapper<KbDocumentPO>()
                .eq(KbDocumentPO::getId, docId)
                .set(KbDocumentPO::getRun, TaskStatusEnum.DONE.getValue())
                .set(KbDocumentPO::getProgress, BigDecimal.ONE)
                .setSql("chunk_count = IFNULL(chunk_count, 0) + " + chunkCount)
                .setSql("token_count = IFNULL(token_count, 0) + " + tokenCount);
        kbDocumentCoreService.update(docUpdate);

        // 知识库：chunk_num / token_num 原子累加（对应 Knowledgebase.update(chunk_num + chunk_num)）
        if (msg.getKbId() != null) {
            LambdaUpdateWrapper<KnowledgeBasePO> kbUpdate = new LambdaUpdateWrapper<KnowledgeBasePO>()
                    .eq(KnowledgeBasePO::getId, Long.valueOf(msg.getKbId()))
                    .setSql("chunk_num = IFNULL(chunk_num, 0) + " + chunkCount)
                    .setSql("token_num = IFNULL(token_num, 0) + " + tokenCount);
            knowledgeBaseCoreService.update(kbUpdate);
        }
    }

    /**
     * 解析失败时将文档标记为失败并记录错误信息（对应 RagFlow set_progress(prog=-1) + run=FAIL）。
     */
    private void failDoc(DocTaskMessage msg, String errorMsg) {
        try {
            KbDocumentPO doc = new KbDocumentPO();
            doc.setId(Long.valueOf(msg.getDocId()));
            doc.setRun(TaskStatusEnum.FAIL.getValue());
            doc.setProgress(PROGRESS_FAIL);
            doc.setProgressMsg(errorMsg);
            doc.setErrorMsg(errorMsg);
            kbDocumentCoreService.updateById(doc);

            KbTaskPO task = new KbTaskPO();
            task.setId(Long.valueOf(msg.getTaskId()));
            task.setProgress(PROGRESS_FAIL);
            task.setProgressMsg(errorMsg);
            kbTaskCoreService.updateById(task);
        } catch (Exception e) {
            log.warn("回写失败状态异常, taskId={}, docId={}", msg.getTaskId(), msg.getDocId(), e);
        }
    }
}