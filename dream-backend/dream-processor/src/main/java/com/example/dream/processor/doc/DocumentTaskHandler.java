package com.example.dream.processor.doc;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.common.dto.DocTaskMessage;
import com.example.dream.common.enums.document.TaskStatusEnum;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.dal.po.KbTaskPO;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.minio.OssService;
import com.example.dream.integration.service.redis.RedisService;
import com.example.dream.service.core.KbDocumentCoreService;
import com.example.dream.service.core.KbTaskCoreService;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import com.example.dream.service.core.chat.retriever.nlp.RagTokenizer;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.dream.dal.po.KnowledgeBasePO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.hashing.LongHashFunction;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * naive_merge 默认分隔符，对应 RagFlow parser_config 默认 delimiter "\n。；！？"。
     */
    private static final String DEFAULT_DELIMITER = "\n。；！？";

    /**
     * 写 ES 的批大小，对应 RagFlow DOC_BULK_SIZE。
     */
    private static final int DOC_BULK_SIZE = 64;

 /**
     * 嵌入模型单批编码的文本数，对应 RagFlow settings.EMBEDDING_BATCH_SIZE。
     */
    private static final int EMBEDDING_BATCH_SIZE = 16;

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

    /**
     * 文件名（标题）向量默认权重，对应 RagFlow embedding() 的 filename_embd_weight 默认 0.1。
     * 最终向量 = title_w * title_vec + (1 - title_w) * content_vec。
     */
    private static final double DEFAULT_FILENAME_EMBD_WEIGHT = 0.1;

    /**
     * embedding 前用于剥离表格 HTML 标签的正则，对应 RagFlow
     * re.sub(r"</?(table|td|caption|tr|th)( [^<>]{0,12})?>", " ", c)。
     */
    private static final String TABLE_TAG_REGEX = "</?(table|td|caption|tr|th)( [^<>]{0,12})?>";

    // ES chunk 文档字段名，对应 RagFlow chunk 字段定义
    private static final String FIELD_ID = "id";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_KB_ID = "kb_id";
    private static final String FIELD_DOC_NAME = "docnm_kwd";
    // 标题分词字段（对应 RagFlow doc["title_tks"] / doc["title_sm_tks"]），随每个 chunk 复用写入
    private static final String FIELD_TITLE_TKS = "title_tks";
    private static final String FIELD_TITLE_SM_TKS = "title_sm_tks";
    // child_delimiters 命中时保留的父级正文（对应 RagFlow d["mom_with_weight"]）
    private static final String FIELD_MOM_CONTENT = "mom_with_weight";
    private static final String FIELD_CONTENT = "content_with_weight";
    private static final String FIELD_CONTENT_LTKS = "content_ltks";
    private static final String FIELD_CONTENT_SM_LTKS = "content_sm_ltks";
    private static final String FIELD_AVAILABLE = "available_int";
    private static final String FIELD_POSITION = "position_int";
    private static final String FIELD_CREATE_TIME = "create_time";
    private static final String FIELD_CREATE_TS = "create_timestamp_flt";
    // auto_keywords / auto_questions 增强字段，对应 RagFlow build_chunks 产出
    private static final String FIELD_IMPORTANT_KWD = "important_kwd";
    private static final String FIELD_IMPORTANT_TKS = "important_tks";
    private static final String FIELD_QUESTION_KWD = "question_kwd";
    private static final String FIELD_QUESTION_TKS = "question_tks";
    private static final String FIELD_METADATA_OBJ = "metadata_obj";
    private static final String FIELD_PAGERANK = "pagerank_fea";

    // parser_config 增强开关键名，对应 RagFlow parser_config
    private static final String CFG_AUTO_KEYWORDS = "auto_keywords";
    // 分块切分相关配置键，对应 RagFlow naive.chunk 读取的 parser_config
    private static final String CFG_CHUNK_TOKEN_NUM = "chunk_token_num";
    private static final String CFG_DELIMITER = "delimiter";
    private static final String CFG_OVERLAPPED_PERCENT = "overlapped_percent";
    private static final String CFG_CHILDREN_DELIMITER = "children_delimiter";
    private static final String CFG_AUTO_QUESTIONS = "auto_questions";
    private static final String CFG_ENABLE_METADATA = "enable_metadata";
    private static final String CFG_METADATA = "metadata";
    private static final String CFG_BUILT_IN_METADATA = "built_in_metadata";

    // 各阶段进度值，对应 RagFlow set_progress 各步骤的进度上报
    private static final BigDecimal PROGRESS_INIT_KB = new BigDecimal("0.1");
    private static final BigDecimal PROGRESS_TEXT_EXTRACTED = new BigDecimal("0.4");
    private static final BigDecimal PROGRESS_CHUNKED = new BigDecimal("0.6");
    private static final BigDecimal PROGRESS_EMBEDDED = new BigDecimal("0.8");
    private static final BigDecimal PROGRESS_FAIL = new BigDecimal("-1");

    /**
     * embedding 分批上报进度的基准值，对应 RagFlow embedding() 回调 0.7 + 0.2 * (i+1)/len。
     */
    private static final double EMBEDDING_PROGRESS_BASE = 0.7;

    /**
     * embedding 分批上报进度的可分配增量，对应 RagFlow embedding() 回调中的 0.2 系数。
     */
    private static final double EMBEDDING_PROGRESS_SPAN = 0.2;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * LLM 增强调用的采样温度，对齐 RagFlow keyword_extraction/question_proposal 的 {"temperature": 0.2}。
     */
    private static final double LLM_TEMPERATURE = 0.2;

    /**
     * LLM 缓存过期时间，对齐 RagFlow set_llm_cache 的 24 * 3600 秒。
     */
    private static final Duration LLM_CACHE_TTL = Duration.ofHours(24);

    /**
     * LLM 缓存 key 中的模型标识（对齐 RagFlow chat_mdl.llm_name 的占位，项目内自洽即可）。
     */
    private static final String LLM_CACHE_MODEL_NAME = "chat";

    // LLM 缓存类别（对齐 RagFlow get/set_llm_cache 的 history 入参）
    private static final String CACHE_HISTORY_KEYWORDS = "keywords";
    private static final String CACHE_HISTORY_QUESTION = "question";
    private static final String CACHE_HISTORY_METADATA = "metadata";

    /**
     * 关键词抽取提示词（对应 RagFlow prompts/keyword_prompt.md，占位符改为 Spring AI {content}/{topn}）。
     */
    private static final String KEYWORD_PROMPT = """
            ## Role
            You are a text analyzer.

            ## Task
            Extract the most important keywords/phrases of a given piece of text content.

            ## Requirements
            - Summarize the text content, and give the top {topn} important keywords/phrases.
            - The keywords MUST be in the same language as the given piece of text content.
            - The keywords are delimited by ENGLISH COMMA.
            - Output keywords ONLY.

            ---

            ## Text Content
            {content}
            """;

    /**
     * 问题生成提示词（对应 RagFlow prompts/question_prompt.md）。
     */
    private static final String QUESTION_PROMPT = """
            ## Role
            You are a text analyzer.

            ## Task
            Propose {topn} questions about a given piece of text content.

            ## Requirements
            - Understand and summarize the text content, and propose the top {topn} important questions.
            - The questions SHOULD NOT have overlapping meanings.
            - The questions SHOULD cover the main content of the text as much as possible.
            - The questions MUST be in the same language as the given piece of text content.
            - One question per line.
            - Output questions ONLY.

            ---

            ## Text Content
            {content}
            """;

    /**
     * 元数据抽取提示词（对应 RagFlow prompts/meta_data.md）。
     */
    private static final String META_DATA_PROMPT = """
            ## Role: Metadata extraction expert.
            ## Rules:
             - Strict Evidence Only: Extract a value ONLY if it is explicitly mentioned in the Content.
             - Enum Filter: For any field with an 'enum' list, the list acts as a strict filter. If no element from the list (or its direct synonym) is found in the Content, you MUST NOT extract that field.
             - No Meta-Inference: Do not infer values based on the document's nature, format, or category. If the text does not literally state the information, treat it as missing.
             - Zero-Hallucination: Never invent information or pick a "likely" value from the enum to fill a field.
             - Empty Result: If no matches are found for any field, or if the content is irrelevant, output ONLY an empty JSON object.
             - Output: ONLY a valid JSON string. No Markdown, no notes.

            ## Schema for extraction:
            {schema}

            ## Content to analyze:
            {content}
            """;

    private final OssService ossService;

    private final EmbeddingModel embeddingModel;

    private final ElasticsearchService elasticsearchService;

    private final KbDocumentCoreService kbDocumentCoreService;

    private final KbTaskCoreService kbTaskCoreService;

    private final KnowledgeBaseCoreService knowledgeBaseCoreService;

    private final RedisService redisService;

    /**
     * 对话大模型，用于 auto_keywords / auto_questions / enable_metadata 增强
     * （对应 RagFlow build_chunks 中的 chat_mdl = LLMBundle(...LLMType.CHAT...)）。
     */
    private final ChatModel chatModel;

    private final ObjectMapper objectMapper;

    private final Tika tika = new Tika();

    /**
     * 中英文混合分词器（对应 RagFlow rag_tokenizer），用于生成 *_tks 分词字段。
     */
    private final RagTokenizer ragTokenizer = RagTokenizer.getInstance();

    /**
     * 处理一条文档解析任务的完整链路。
     *
     * @param msg 任务消息
     */
    public void handle(DocTaskMessage msg) {
        Long taskId = msg.getTaskId();
        // 开头取消检查（对应 RagFlow do_handle_task 起始 has_canceled(task_id)）
        if (hasCanceled(taskId)) {
            markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
            return;
        }

        boolean inserted = false;
        try {
            // 文件大小超限校验（对应 RagFlow DOC_MAXIMUM_SIZE 检查）
            if (msg.getSize() != null && msg.getSize() > DOC_MAXIMUM_SIZE) {
                failDoc(msg, "文件超过大小上限(<= 128MB)");
                return;
            }

            // 1) initKb：绑定嵌入模型 + 探测维度 + 建索引
            initKb(msg);

            // 2) buildChunks：拉文件 -> Tika 提取文本 -> 分块
            List<Map<String, Object>> chunks = buildChunks(msg);
            if (chunks.isEmpty()) {
                // 无内容：直接标记完成（对应 RagFlow "No chunk built"）
                markProgress(msg, BigDecimal.ONE, "未从文档中解析出内容");
                finishDoc(msg, 0, 0);
                return;
            }

            // 3) embedding：生成向量并挂 q_{dim}_vec，返回真实 token 消耗（对应 RagFlow tk_count, vector_size）
            int tokenCount = embedding(msg, chunks).tokenCount();

            // chunk 数量按 id 去重（对应 RagFlow chunk_count = len(set([chunk["id"] ...]))）
            long chunkCount = chunks.stream()
                    .map(ck -> String.valueOf(ck.get(FIELD_ID)))
                    .distinct()
                    .count();

            // 入库前取消检查（对应 RagFlow _maybe_insert_chunks 中的 has_canceled）
            if (hasCanceled(taskId)) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                return;
            }

            // 4) insertChunks：批量写 ES + 回写统计
            insertChunks(msg, chunks);
            inserted = true;

            // 入库后取消检查（对应 RagFlow insert 后的 has_canceled，取消则不落成功状态）
            if (hasCanceled(taskId)) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                return;
            }

            finishDoc(msg, (int) chunkCount, tokenCount);
            markProgress(msg, BigDecimal.ONE, "解析完成，共 " + chunkCount + " 个分块");
        } catch (RuntimeException e) {
            failDoc(msg, "解析失败: " + e.getMessage());
            // 保留原始异常类型与堆栈，交由上层（消费者）记录日志，不做无意义包装
            throw e;
        } catch (Exception e) {
            failDoc(msg, "解析失败: " + e.getMessage());
            throw new IllegalStateException("文档解析任务处理失败, docId=" + msg.getDocId(), e);
        } finally {
            // 取消时清理已写入 ES 的分块（对应 RagFlow do_handle_task finally 块的 docStore 删除）
            if (inserted && hasCanceled(taskId)) {
                cleanupChunksOnCancel(msg);
            }
        }
    }

    /**
     * 检查任务是否已被取消（对应 RagFlow has_canceled：读取 Redis "{task_id}-cancel" key）。
     *
     * @param taskId 任务 ID
     * @return 是否已取消
     */
    private boolean hasCanceled(Long taskId) {
        if (taskId == null) {
            return false;
        }
        try {
            if (redisService.get(DocTaskConstants.cancelKey(taskId)) != null) {
                log.info("Task: {} has been canceled", taskId);
                return true;
            }
        } catch (Exception e) {
            log.warn("检查任务取消状态异常, taskId={}", taskId, e);
        }
        return false;
    }

    /**
     * 任务取消时，从 ES 删除该文档已写入的全部分块。
     * 对应 RagFlow do_handle_task finally 块：index_exist 后 docStoreConn.delete({"doc_id": ...})。
     */
    private void cleanupChunksOnCancel(DocTaskMessage msg) {
        try {
            String index = DocTaskConstants.indexName(msg.getUserId());
            if (elasticsearchService.indexExists(index)) {
                long removed = elasticsearchService.deleteByTerm(index, FIELD_DOC_ID, String.valueOf(msg.getDocId()));
                log.info("任务取消，已从 ES 删除文档分块, docId={}, index={}, removed={}", msg.getDocId(), index, removed);
            }
        } catch (Exception e) {
            log.warn("任务取消清理 ES 分块失败, taskId={}, docId={}", msg.getTaskId(), msg.getDocId(), e);
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
        String index = DocTaskConstants.indexName(msg.getUserId());
        elasticsearchService.createChunkIndexIfAbsent(index, vectorSize);

        markProgress(msg, PROGRESS_INIT_KB, "已绑定嵌入模型(维度=" + vectorSize + ")，索引就绪");
        return vectorSize;
    }

    /**
     * buildChunks：从 MinIO 拉取文件二进制，用 Tika 提取纯文本，再严格对齐 RagFlow naive.chunk 的
     * naive_merge + tokenize_chunks 逻辑分块。
     *
     * <p>对齐点：
     * <ol>
     *   <li>doc 级 docnm_kwd / title_tks / title_sm_tks（对应 RagFlow doc 字典）；</li>
     *   <li>parser_config 读取 chunk_token_num / delimiter / overlapped_percent / children_delimiter；</li>
     *   <li>naiveMerge 按 delimiter 分句、按 chunk_token_num 合并、按 overlapped_percent 重叠、
     *       反引号自定义分隔符独立成块（对应 naive_merge）；</li>
     *   <li>tokenizeChunks 逐块生成 content_ltks / content_sm_ltks，命中 children_delimiter 时按
     *       子分隔符二次拆分并保留 mom_with_weight（对应 tokenize_chunks + split_with_pattern）。</li>
     * </ol>
     * 文本提取仍使用 Tika（不含 DeepDOC/OCR/表格/图片管线）。</p>
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

        if (StringUtils.isBlank(text)) {
            return new ArrayList<>();
        }

        // 解析文档 parser_config（对应 RagFlow task["parser_config"]）
        Map<String, Object> parserConfig = parseParserConfig(msg);
        // pagerank：对应 RagFlow doc[PAGERANK_FLD] = int(task["pagerank"])，非零才写入
        int pagerank = getInt(parserConfig.get("pagerank"), 0);
        // chunk_token_num / delimiter / overlapped_percent：对应 RagFlow naive.chunk 读取的分块配置
        int chunkTokenNum = getInt(parserConfig.get(CFG_CHUNK_TOKEN_NUM), CHUNK_TOKEN_NUM);
        String delimiter = getString(parserConfig.get(CFG_DELIMITER), DEFAULT_DELIMITER);
        int overlappedPercent = normalizeOverlappedPercent(parserConfig.get(CFG_OVERLAPPED_PERCENT));
        // children_delimiter：对应 RagFlow naive.chunk 的 child_deli，命中则触发子分块
        String childDeliPattern = buildChildDelimiterPattern(getString(parserConfig.get(CFG_CHILDREN_DELIMITER), ""));

        // doc 级字段：docnm_kwd / title_tks / title_sm_tks（对应 RagFlow doc 字典，逐块复用）
        String docName = msg.getName();
        // 去扩展名后分词（对应 RagFlow rag_tokenizer.tokenize(re.sub(r"\.[a-zA-Z]+$", "", filename))）
        String titleForTks = StringUtils.isBlank(docName) ? "" : docName.replaceAll("\\.[a-zA-Z]+$", "");
        String titleTks = ragTokenizer.tokenize(titleForTks);
        String titleSmTks = ragTokenizer.fineGrainedTokenize(titleTks);

        // 按 RagFlow naive_merge 切分为文本块
        List<String> segments = naiveMerge(text, chunkTokenNum, delimiter, overlappedPercent);

        List<Map<String, Object>> chunks = new ArrayList<>(segments.size());
        String createTime = LocalDateTime.now().format(TIME_FMT);
        double createTs = System.currentTimeMillis() / 1000.0;

        // 对应 RagFlow tokenize_chunks：逐块包装为 ES 文档
        int position = 0;
        for (String seg : segments) {
            // 对应 Python：if len(ck.strip()) == 0: continue
            if (StringUtils.isBlank(seg)) {
                continue;
            }

            if (StringUtils.isNotBlank(childDeliPattern)) {
                // 命中 children_delimiter：保留父级正文 mom_with_weight，再按子分隔符二次拆分为多块
                // （对应 tokenize_chunks: d["mom_with_weight"]=ck; split_with_pattern(...)）
                for (String sub : splitWithPattern(childDeliPattern, seg)) {
                    Map<String, Object> ck = newChunk(msg, docName, titleTks, titleSmTks, createTime, createTs, pagerank,
                            sub.trim(), position);
                    ck.put(FIELD_MOM_CONTENT, seg);
                    chunks.add(ck);
                    position++;
                }
                continue;
            }

            Map<String, Object> ck = newChunk(msg, docName, titleTks, titleSmTks, createTime, createTs, pagerank,
                    seg.trim(), position);
            chunks.add(ck);
            position++;
        }
        markProgress(msg, PROGRESS_CHUNKED, "分块完成，共 " + chunks.size() + " 块");

        // 对应 RagFlow build_chunks：按 parser_config 依次执行三段 LLM 增强
        if (getInt(parserConfig.get(CFG_AUTO_KEYWORDS), 0) > 0) {
            autoKeywords(msg, chunks, getInt(parserConfig.get(CFG_AUTO_KEYWORDS), 0));
        }
        if (getInt(parserConfig.get(CFG_AUTO_QUESTIONS), 0) > 0) {
            autoQuestions(msg, chunks, getInt(parserConfig.get(CFG_AUTO_QUESTIONS), 0));
        }
        if (getBool(parserConfig.get(CFG_ENABLE_METADATA), false)
                && (parserConfig.get(CFG_METADATA) != null || parserConfig.get(CFG_BUILT_IN_METADATA) != null)) {
            genMetadata(msg, chunks, parserConfig);
        }
        return chunks;
    }

    /**
     * auto_keywords：为每个分块抽取关键词并写入 important_kwd / important_tks。
     * 对应 RagFlow build_chunks 中的 doc_keyword_extraction：
     * important_kwd = [k for k in re.split(r"[,，;；、\r\n]+", cached) if k.strip()]；
     * important_tks = rag_tokenizer.tokenize(" ".join(important_kwd))。
     */
    private void autoKeywords(DocTaskMessage msg, List<Map<String, Object>> chunks, int topn) {
        markProgress(msg, PROGRESS_CHUNKED, "开始为每个分块生成关键词 ...");
        for (Map<String, Object> d : chunks) {
            if (hasCanceled(msg.getTaskId())) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                return;
            }
            String content = String.valueOf(d.get(FIELD_CONTENT));
            String cached = keywordExtraction(content, topn);
            if (StringUtils.isBlank(cached)) {
                continue;
            }
            // 对应 Python：re.split(r"[,，;；、\r\n]+", cached) 过滤空白
            List<String> kwd = new ArrayList<>();
            for (String k : cached.split("[,，;；、\\r\\n]+")) {
                if (StringUtils.isNotBlank(k)) {
                    kwd.add(k.trim());
                }
            }
            if (kwd.isEmpty()) {
                continue;
            }
            d.put(FIELD_IMPORTANT_KWD, kwd);
            d.put(FIELD_IMPORTANT_TKS, ragTokenizer.tokenize(String.join(" ", kwd)));
        }
        markProgress(msg, PROGRESS_CHUNKED, "关键词生成完成，共 " + chunks.size() + " 块");
    }

    /**
     * auto_questions：为每个分块生成问题并写入 question_kwd / question_tks。
     * 对应 RagFlow build_chunks 中的 doc_question_proposal：
     * question_kwd = cached.split("\n")；question_tks = rag_tokenizer.tokenize("\n".join(question_kwd))。
     */
    private void autoQuestions(DocTaskMessage msg, List<Map<String, Object>> chunks, int topn) {
        markProgress(msg, PROGRESS_CHUNKED, "开始为每个分块生成问题 ...");
        for (Map<String, Object> d : chunks) {
            if (hasCanceled(msg.getTaskId())) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                return;
            }
            String content = String.valueOf(d.get(FIELD_CONTENT));
            String cached = questionProposal(content, topn);
            if (StringUtils.isBlank(cached)) {
                continue;
            }
            // 对应 Python：question_kwd = cached.split("\n")
            List<String> questions = new ArrayList<>(List.of(cached.split("\n")));
            d.put(FIELD_QUESTION_KWD, questions);
            d.put(FIELD_QUESTION_TKS, ragTokenizer.tokenize(String.join("\n", questions)));
        }
        markProgress(msg, PROGRESS_CHUNKED, "问题生成完成，共 " + chunks.size() + " 块");
    }

    /**
     * enable_metadata：为每个分块生成结构化元数据，合并去重后持久化到文档 meta_fields，并从分块删除 metadata_obj。
     * 严格对齐 RagFlow build_chunks 的 gen_metadata_task 段：
     * <ol>
     *   <li>合并 metadata + built_in_metadata 构建 schema，逐块调 LLM（带缓存）抽取 metadata_obj；</li>
     *   <li>metadata = update_metadata_to(metadata, doc["metadata_obj"])，随后 del doc["metadata_obj"]
     *       （即分块<strong>不携带</strong> metadata_obj 入 ES）；</li>
     *   <li>再与文档已有元数据合并：metadata = update_metadata_to(metadata, existing_meta)；</li>
     *   <li>DocMetadataService.update_document_metadata → 落库到 kb_document.meta_fields。</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private void genMetadata(DocTaskMessage msg, List<Map<String, Object>> chunks, Map<String, Object> parserConfig) {
        markProgress(msg, PROGRESS_CHUNKED, "开始为每个分块生成元数据 ...");
        Object metadataConf = parserConfig.get(CFG_METADATA);
        Object builtInMetadata = parserConfig.get(CFG_BUILT_IN_METADATA);
        String schema = buildMetadataSchema(metadataConf, builtInMetadata);

        // 逐块抽取 metadata_obj（对应 Python 先并发生成各块 metadata_obj）
        for (Map<String, Object> d : chunks) {
            if (hasCanceled(msg.getTaskId())) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                return;
            }
            String content = String.valueOf(d.get(FIELD_CONTENT));
            String cached = generateMetadata(schema, content);
            if (StringUtils.isNotBlank(cached)) {
                d.put(FIELD_METADATA_OBJ, cached);
            }
        }

        // 合并各块 metadata_obj 并从分块删除该字段
        // （对应 Python：metadata = update_metadata_to(metadata, doc["metadata_obj"]); del doc["metadata_obj"]）
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (Map<String, Object> d : chunks) {
            Object obj = d.remove(FIELD_METADATA_OBJ);
            metadata = updateMetadataTo(metadata, obj);
        }

        // 与文档已有元数据合并并落库到 kb_document.meta_fields
        // （对应 Python：existing_meta = get_document_metadata(doc_id); update_metadata_to; update_document_metadata）
        if (!metadata.isEmpty()) {
            Map<String, Object> existing = getDocumentMetadata(msg.getDocId());
            metadata = updateMetadataTo(metadata, existing);
            updateDocumentMetadata(msg.getDocId(), metadata);
        }
        markProgress(msg, PROGRESS_CHUNKED, "元数据生成完成，共 " + chunks.size() + " 块");
    }

    /**
     * 将 meta 合并进 metadata，严格对齐 RagFlow update_metadata_to：
     * <ul>
     *   <li>meta 为空/非法直接返回 metadata；字符串先按 JSON 解析；</li>
     *   <li>value 为 list：仅保留字符串元素、去重；空列表跳过；</li>
     *   <li>value 非 list 非 string：跳过；</li>
     *   <li>key 不存在则直接放入；存在且为 list 则合并去重；否则覆盖。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> updateMetadataTo(Map<String, Object> metadata, Object meta) {
        if (meta == null) {
            return metadata;
        }
        Map<String, Object> metaMap;
        if (meta instanceof Map<?, ?> m) {
            metaMap = (Map<String, Object>) m;
        } else if (meta instanceof String s) {
            if (StringUtils.isBlank(s)) {
                return metadata;
            }
            try {
                metaMap = objectMapper.readValue(s, Map.class);
            } catch (Exception e) {
                log.warn("metadata JSON 解析失败, 忽略: {}", s, e);
                return metadata;
            }
        } else {
            return metadata;
        }
        if (metaMap == null || metaMap.isEmpty()) {
            return metadata;
        }

        for (Map.Entry<String, Object> entry : metaMap.entrySet()) {
            String k = entry.getKey();
            Object v = entry.getValue();
            if (v instanceof List<?> list) {
                // 仅保留字符串元素并去重（对应 Python：v = [vv for vv in v if isinstance(vv, str)]; dedupe）
                List<String> strs = new ArrayList<>();
                for (Object vv : list) {
                    if (vv instanceof String sv) {
                        strs.add(sv);
                    }
                }
                if (strs.isEmpty()) {
                    continue;
                }
                v = dedupeList(strs);
            } else if (!(v instanceof String)) {
                // 非 list 非 string：跳过（对应 Python 的 continue）
                continue;
            }

            if (!metadata.containsKey(k)) {
                metadata.put(k, v);
                continue;
            }
            Object existing = metadata.get(k);
            if (existing instanceof List<?> exList) {
                List<String> merged = new ArrayList<>();
                for (Object e : exList) {
                    if (e instanceof String es) {
                        merged.add(es);
                    }
                }
                if (v instanceof List<?> vList) {
                    for (Object e : vList) {
                        if (e instanceof String es) {
                            merged.add(es);
                        }
                    }
                } else if (v instanceof String vs) {
                    merged.add(vs);
                }
                metadata.put(k, dedupeList(merged));
            } else {
                metadata.put(k, v);
            }
        }
        return metadata;
    }

    /**
     * 保序去重（对应 RagFlow dedupe_list）。
     */
    private List<String> dedupeList(List<String> list) {
        List<String> result = new ArrayList<>();
        for (String s : list) {
            if (!result.contains(s)) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * 读取文档已有元数据（对应 RagFlow DocMetadataService.get_document_metadata）。
     * 从 kb_document.meta_fields（JSON 字符串）解析为 Map，空/非法返回空 Map。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getDocumentMetadata(Long docId) {
        try {
            KbDocumentPO doc = kbDocumentCoreService.getById(docId);
            if (doc == null || StringUtils.isBlank(doc.getMetaFields())) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> parsed = objectMapper.readValue(doc.getMetaFields(), Map.class);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("读取文档元数据失败, docId={}", docId, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 持久化文档元数据到 kb_document.meta_fields（对应 RagFlow DocMetadataService.update_document_metadata）。
     */
    private void updateDocumentMetadata(Long docId, Map<String, Object> metadata) {
        try {
            String json = objectMapper.writeValueAsString(metadata);
            KbDocumentPO doc = new KbDocumentPO();
            doc.setId(docId);
            doc.setMetaFields(json);
            kbDocumentCoreService.updateById(doc);
            log.info("已持久化文档元数据, docId={}, meta_fields={}", docId, json);
        } catch (Exception e) {
            log.warn("持久化文档元数据失败, docId={}", docId, e);
        }
    }

    /**
     * 组装单个 chunk 的 ES 文档字段 Map（对应 RagFlow tokenize + doc 字典字段）。
     *
     * @param content 已 trim 的正文（content_with_weight）
     */
    private Map<String, Object> newChunk(DocTaskMessage msg, String docName, String titleTks, String titleSmTks,
                                         String createTime, double createTs, int pagerank,
                                         String content, int position) {
        Map<String, Object> ck = new LinkedHashMap<>();
        // 对应 RagFlow：d["id"] = xxhash.xxh64((content_with_weight + str(doc_id)).encode()).hexdigest()
        ck.put(FIELD_ID, chunkId(content, msg.getDocId()));
        ck.put(FIELD_DOC_ID, msg.getDocId());
        ck.put(FIELD_KB_ID, msg.getKbId());
        // doc 级字段：docnm_kwd / title_tks / title_sm_tks（对应 RagFlow doc 字典）
        ck.put(FIELD_DOC_NAME, docName);
        ck.put(FIELD_TITLE_TKS, titleTks);
        ck.put(FIELD_TITLE_SM_TKS, titleSmTks);
        ck.put(FIELD_CONTENT, content);
        // content_ltks 主分词 + content_sm_ltks 细粒度分词（对应 RagFlow tokenize()）；
        // 先剥离表格 HTML 标签再分词（对应 tokenize: re.sub(r"</?(table|td|caption|tr|th)...>", " ", txt)）
        String forTokenize = content.replaceAll(TABLE_TAG_REGEX, " ");
        String contentLtks = ragTokenizer.tokenize(forTokenize);
        ck.put(FIELD_CONTENT_LTKS, contentLtks);
        ck.put(FIELD_CONTENT_SM_LTKS, ragTokenizer.fineGrainedTokenize(contentLtks));
        ck.put(FIELD_AVAILABLE, 1);
        ck.put(FIELD_POSITION, position);
        ck.put(FIELD_CREATE_TIME, createTime);
        ck.put(FIELD_CREATE_TS, createTs);
        // 对应 RagFlow：if task["pagerank"]: doc[PAGERANK_FLD] = int(task["pagerank"])
        if (pagerank != 0) {
            ck.put(FIELD_PAGERANK, pagerank);
        }
        return ck;
    }

    /**
     * naiveMerge：严格对齐 RagFlow rag.nlp.naive_merge。
     * <p>规则：
     * <ol>
     *   <li>统一换行（\r\n、\r → \n），使 delimiter 中的 \n 生效；</li>
     *   <li>若 delimiter 含反引号自定义分隔符（如 {@code `\n\n`}），则忽略 chunk_token_num，
     *       每个自定义分隔符切出的段落独立成块（对应 custom_delimiters 分支）；</li>
     *   <li>否则按句子分隔符拆分超长 section，再通过 addChunk 按
     *       chunk_token_num*(100-overlapped_percent)/100 阈值合并、按 overlapped_percent 追加重叠前缀。</li>
     * </ol></p>
     */
    private List<String> naiveMerge(String text, int chunkTokenNum, String delimiter, int overlappedPercent) {
        if (StringUtils.isEmpty(text)) {
            return new ArrayList<>();
        }
        // 对应 Python：sections = [(s.replace("\r\n","\n").replace("\r","\n"), pos)]
        String section = text.replace("\r\n", "\n").replace("\r", "\n");

        // 反引号自定义分隔符：对应 custom_delimiters 分支（忽略 chunk_token_num，各段独立成块）
        List<String> customDelimiters = extractCustomDelimiters(delimiter);
        if (!CollectionUtils.isEmpty(customDelimiters)) {
            List<String> cks = new ArrayList<>();
            String customPattern = customDelimiters.stream()
                    .sorted((a, b) -> b.length() - a.length())
                    .distinct()
                    .map(Pattern::quote)
                    .reduce((a, b) -> a + "|" + b)
                    .orElse("");
            // 对应 Python：re.split(r"(pattern)", sec, flags=DOTALL)，保留分隔符组后过滤掉分隔符本身
            for (String subSec : splitKeepDelimiter(section, customPattern)) {
                if (subSec == null || subSec.matches(customPattern)) {
                    continue;
                }
                String t = "\n" + subSec;
                cks.add(t);
            }
            return cks;
        }

        // 句子分隔符 + chunk_token_num 合并 + overlap（对应 add_chunk 逻辑）
        List<String> cks = new ArrayList<>();
        cks.add("");
        List<Integer> tkNums = new ArrayList<>();
        tkNums.add(0);

        String dels = getDelimiters(delimiter);
        if (StringUtils.isEmpty(dels) || numTokens(section) < chunkTokenNum) {
            addChunk(cks, tkNums, "\n" + section, chunkTokenNum, overlappedPercent);
        } else {
            for (String subSec : splitKeepDelimiter(section, dels)) {
                if (StringUtils.isEmpty(subSec) || subSec.matches(dels)) {
                    continue;
                }
                addChunk(cks, tkNums, "\n" + subSec, chunkTokenNum, overlappedPercent);
            }
        }
        return cks;
    }

    /**
     * addChunk：对应 RagFlow naive_merge 内嵌的 add_chunk —
     * 当前块为空或已超阈值则起新块（并追加上一块尾部作为重叠前缀），否则并入当前块。
     */
    private void addChunk(List<String> cks, List<Integer> tkNums, String t, int chunkTokenNum, int overlappedPercent) {
        int tnum = numTokens(t);
        // 对应 Python：if cks[-1] == "" or tk_nums[-1] > chunk_token_num*(100-overlapped)/100.0
        double threshold = chunkTokenNum * (100 - overlappedPercent) / 100.0;
        if (cks.getLast().isEmpty() || tkNums.getLast() > threshold) {
            // 追加上一块尾部的重叠前缀（对应 Python overlapped[...:]）
            String last = cks.getLast();
            String overlapped = last.replaceAll(TABLE_TAG_REGEX, " ");
            int from = (int) (overlapped.length() * (100 - overlappedPercent) / 100.0);
            from = Math.max(0, Math.min(from, overlapped.length()));
            t = overlapped.substring(from) + t;
            tnum = numTokens(t);
            cks.add(t);
            tkNums.add(tnum);
        } else {
            cks.set(cks.size() - 1, cks.getLast() + t);
            tkNums.set(tkNums.size() - 1, tkNums.getLast() + tnum);
        }
    }

    /**
     * splitWithPattern：对应 RagFlow split_with_pattern —
     * 用子分隔符（保留分隔符）将父级正文切分为多段，非法正则时退回单段。
     */
    private List<String> splitWithPattern(String pattern, String content) {
        List<String> docs = new ArrayList<>();
        List<String> txts;
        try {
            txts = splitKeepDelimiter(content, pattern);
        } catch (Exception e) {
            log.warn("children_delimiter 正则非法 '{}'，退回单段: {}", pattern, e.getMessage());
            docs.add(content);
            return docs;
        }
        // 对应 Python：for j in range(0, len(txts), 2): txt = txts[j]; if j+1<len: txt += txts[j+1]
        for (int j = 0; j < txts.size(); j += 2) {
            String txt = txts.get(j);
            if (StringUtils.isEmpty(txt)) {
                continue;
            }
            if (j + 1 < txts.size()) {
                txt += txts.get(j + 1);
            }
            docs.add(txt);
        }
        return docs;
    }

    /**
     * 用正则分隔符切分并保留分隔符组（对应 Python re.split(r"(pattern)", s, flags=DOTALL)）。
     * 返回列表交替为 [正文, 分隔符, 正文, 分隔符, ...]。
     */
    private List<String> splitKeepDelimiter(String s, String pattern) {
        List<String> result = new ArrayList<>();
        Pattern p = Pattern.compile("(" + pattern + ")", Pattern.DOTALL);
        Matcher m = p.matcher(s);
        int last = 0;
        while (m.find()) {
            result.add(s.substring(last, m.start()));
            result.add(m.group(1));
            last = m.end();
        }
        result.add(s.substring(last));
        return result;
    }

    /**
     * getDelimiters：对应 RagFlow get_delimiters —
     * 将 delimiter 字符串拆为「反引号包裹的多字符分隔符 + 逐个单字符分隔符」，按长度降序拼为正则。
     */
    private String getDelimiters(String delimiters) {
        if (StringUtils.isEmpty(delimiters)) {
            return "";
        }
        List<String> dels = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`", Pattern.CASE_INSENSITIVE).matcher(delimiters);
        int s = 0;
        while (m.find()) {
            int f = m.start();
            int t = m.end();
            dels.add(m.group(1));
            for (int i = s; i < f; i++) {
                dels.add(String.valueOf(delimiters.charAt(i)));
            }
            s = t;
        }
        for (int i = s; i < delimiters.length(); i++) {
            dels.add(String.valueOf(delimiters.charAt(i)));
        }
        // 长度降序 + 去空 + 转义（对应 Python sort(-len) + re.escape）
        return dels.stream()
                .filter(d -> !d.isEmpty())
                .sorted((a, b) -> b.length() - a.length())
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }

    /**
     * 提取 delimiter 中反引号包裹的自定义分隔符（对应 naive_merge 的 custom_delimiters）。
     */
    private List<String> extractCustomDelimiters(String delimiter) {
        List<String> result = new ArrayList<>();
        if (StringUtils.isEmpty(delimiter)) {
            return result;
        }
        Matcher m = Pattern.compile("`([^`]+)`").matcher(delimiter);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    /**
     * 由 children_delimiter 配置构建子分隔符正则（对应 naive.chunk 的 child_deli 构建）。
     * 支持 unicode 转义（\n 等）与反引号自定义分隔符，多个以 | 连接。
     */
    private String buildChildDelimiterPattern(String childrenDelimiter) {
        if (StringUtils.isBlank(childrenDelimiter)) {
            return "";
        }
        // 反引号内自定义分隔符按字面量转义；反引号外按整体作为正则片段（对齐 Python child_deli 拼接）
        List<String> custom = extractCustomDelimiters(childrenDelimiter);
        String stripped = childrenDelimiter.replaceAll("`([^`]+)`", "");
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(stripped)) {
            sb.append(stripped);
        }
        if (!custom.isEmpty()) {
            String customPattern = custom.stream()
                    .sorted((a, b) -> b.length() - a.length())
                    .distinct()
                    .map(Pattern::quote)
                    .reduce((a, b) -> a + "|" + b)
                    .orElse("");
            if (sb.length() > 0 && StringUtils.isNotBlank(customPattern)) {
                sb.append("|");
            }
            sb.append(customPattern);
        }
        return sb.toString();
    }

    /**
     * 归一化 overlapped_percent 到 [0, 100]（对应 RagFlow normalize_overlapped_percent）。
     */
    private int normalizeOverlappedPercent(Object v) {
        int p = getInt(v, 0);
        if (p < 0) {
            return 0;
        }
        return Math.min(p, 100);
    }

    /**
     * numTokens：近似 token 计数，对齐 RagFlow num_tokens_from_string 的语义（无 tiktoken 依赖）。
     * <p>规则：CJK 字符按 1 token/字，连续 ASCII 词按 ~4 字符/token（cl100k_base 经验值），
     * 其余符号按 1 token 计。仅用于分块阈值判定，无需与 tiktoken 完全一致。</p>
     */
    private int numTokens(String s) {
        if (StringUtils.isEmpty(s)) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x7F && (Character.isLetterOrDigit(c))) {
                asciiRun++;
            } else {
                if (asciiRun > 0) {
                    tokens += (int) Math.ceil(asciiRun / 4.0);
                    asciiRun = 0;
                }
                if (Character.isWhitespace(c)) {
                    continue;
                }
                // CJK 或其它符号：1 token
                tokens += 1;
            }
        }
        if (asciiRun > 0) {
            tokens += (int) Math.ceil(asciiRun / 4.0);
        }
        return tokens;
    }

    /**
     * 读取字符串配置值（兼容 String / 其它），为空返回默认值。
     */
    private String getString(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v);
        return StringUtils.isEmpty(s) ? def : s;
    }

    /**
     * 生成分块 ID：内容 + docId 的 xxHash64 十六进制。
     * 严格对齐 RagFlow：xxhash.xxh64((content_with_weight + str(doc_id)).encode("utf-8","surrogatepass")).hexdigest()，
     * 同内容 + 同文档产出稳定 id，保证重复入库幂等。
     * <p>Python xxhash 的 hexdigest 为 8 字节大端十六进制（固定 16 位，不足补零），
     * 此处将 xxHash64 的 long 结果按无符号大端格式化为 16 位 hex，与之完全一致。</p>
     */
    private String chunkId(String content, Long docId) {
        String base = content + docId;
        long h = LongHashFunction.xx().hashBytes(base.getBytes(StandardCharsets.UTF_8));
        return String.format("%016x", h);
    }

    /**
     * 解析文档 parser_config（JSON 字符串）为 Map，对应 RagFlow task["parser_config"]。
     * 解析失败或为空时返回空 Map，等价于所有增强开关关闭。
     */
    private Map<String, Object> parseParserConfig(DocTaskMessage msg) {
        String cfg = msg.getParserConfig();
        if (StringUtils.isBlank(cfg)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(cfg, Map.class);
            return parsed != null ? parsed : new LinkedHashMap<>();
        } catch (Exception e) {
            log.warn("解析 parser_config 失败, docId={}, config={}", msg.getDocId(), cfg, e);
            return new LinkedHashMap<>();
        }
    }

    /**
     * 读取整型配置值（兼容 Number / String），非法或为空返回默认值。
     */
    private int getInt(Object v, int def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * 读取布尔配置值（兼容 Boolean / String / Number），非法或为空返回默认值。
     */
    private boolean getBool(Object v, boolean def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /**
     * 关键词抽取（带缓存）：严格对齐 RagFlow build_chunks 的 doc_keyword_extraction —
     * 先查 get_llm_cache(content, "keywords", {"topn": topn})，miss 才调 LLM 并 set_llm_cache 回写。
     */
    private String keywordExtraction(String content, int topn) {
        String genconf = "{topn=" + topn + "}";
        String hit = getLlmCache(content, CACHE_HISTORY_KEYWORDS, genconf);
        if (StringUtils.isNotBlank(hit)) {
            return hit;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("content", StringUtils.isBlank(content) ? "" : content);
        vars.put("topn", topn);
        String prompt = new PromptTemplate(KEYWORD_PROMPT).render(vars);
        String out = callChat(prompt);
        setLlmCache(content, out, CACHE_HISTORY_KEYWORDS, genconf);
        return out;
    }

    /**
     * 问题生成（带缓存）：严格对齐 RagFlow build_chunks 的 doc_question_proposal —
     * 先查 get_llm_cache(content, "question", {"topn": topn})，miss 才调 LLM 并回写。
     */
    private String questionProposal(String content, int topn) {
        String genconf = "{topn=" + topn + "}";
        String hit = getLlmCache(content, CACHE_HISTORY_QUESTION, genconf);
        if (StringUtils.isNotBlank(hit)) {
            return hit;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("content", StringUtils.isBlank(content) ? "" : content);
        vars.put("topn", topn);
        String prompt = new PromptTemplate(QUESTION_PROMPT).render(vars);
        String out = callChat(prompt);
        setLlmCache(content, out, CACHE_HISTORY_QUESTION, genconf);
        return out;
    }

    /**
     * 元数据抽取（带缓存）：严格对齐 RagFlow build_chunks 的 gen_metadata_task —
     * 先查 get_llm_cache(content, "metadata", metadata_conf)，miss 才调 LLM 并回写。
     * genconf 使用 schema 字符串（同 schema 命中同缓存），与 Python metadata_conf 语义一致。
     */
    private String generateMetadata(String schema, String content) {
        String genconf = schema == null ? "{}" : schema;
        String hit = getLlmCache(content, CACHE_HISTORY_METADATA, genconf);
        if (StringUtils.isNotBlank(hit)) {
            return hit;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("schema", schema == null ? "{}" : schema);
        vars.put("content", StringUtils.isBlank(content) ? "" : content);
        String prompt = new PromptTemplate(META_DATA_PROMPT).render(vars);
        String out = callChat(prompt);
        setLlmCache(content, out, CACHE_HISTORY_METADATA, genconf);
        return out;
    }

    /**
     * 合并 metadata 与 built_in_metadata 生成用于 LLM 抽取的 schema 字符串。
     * 对应 RagFlow gen_metadata_task 中 metadata_conf 与 built_in_metadata 的合并 + turn2jsonschema。
     */
    private String buildMetadataSchema(Object metadataConf, Object builtInMetadata) {
        try {
            Map<String, Object> schema = new LinkedHashMap<>();
            if (metadataConf != null) {
                schema.put("metadata", metadataConf);
            }
            if (builtInMetadata != null) {
                schema.put("built_in_metadata", builtInMetadata);
            }
            return objectMapper.writeValueAsString(schema);
        } catch (Exception e) {
            log.warn("构建 metadata schema 失败", e);
            return "{}";
        }
    }

    /**
     * 统一的 LLM 调用：以 temperature=0.2 请求，去除推理模型思维链，命中 **ERROR** 返回空串。
     * 严格对齐 RagFlow keyword_extraction/question_proposal/gen_metadata：
     * chat_mdl.async_chat(prompt, ..., {"temperature": 0.2}); kwd = re.sub(r"^.*</think>", "", kwd); if "**ERROR**": return ""。
     */
    private String callChat(String prompt) {
        try {
            ChatOptions options = ChatOptions.builder().temperature(LLM_TEMPERATURE).build();
            ChatResponse resp = chatModel.call(new Prompt(prompt, options));
            String out = (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null)
                    ? null : resp.getResult().getOutput().getText();
            if (StringUtils.isBlank(out)) {
                return "";
            }
            // 去除思维链：从开头贪婪匹配到最后一个 </think>
            out = out.replaceFirst("(?s)^.*</think>", "");
            if (out.contains("**ERROR**")) {
                return "";
            }
            return out.trim();
        } catch (Exception e) {
            log.warn("LLM 调用失败", e);
            return "";
        }
    }

    /**
     * 读取 LLM 缓存，miss 返回 null。
     * 对应 RagFlow get_llm_cache(llmnm, txt, history, genconf)：
     * key = xxh64(llmName + txt + history + genconf).hexdigest()，命中返回缓存字符串。
     *
     * @param txt     缓存正文（分块内容）
     * @param history 缓存类别（keywords/question/metadata，对齐 Python history 入参）
     * @param genconf 生成配置（如 topn 或 metadata schema，对齐 Python genconf 入参）
     */
    private String getLlmCache(String txt, String history, String genconf) {
        try {
            Object v = redisService.get(llmCacheKey(txt, history, genconf));
            return v == null ? null : String.valueOf(v);
        } catch (Exception e) {
            log.debug("读取 LLM 缓存失败, history={}", history, e);
            return null;
        }
    }

    /**
     * 写入 LLM 缓存（TTL 24 小时）。
     * 对应 RagFlow set_llm_cache(llmnm, txt, v, history, genconf)：REDIS_CONN.set(k, v, 24*3600)。
     */
    private void setLlmCache(String txt, String value, String history, String genconf) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            redisService.set(llmCacheKey(txt, history, genconf), value, LLM_CACHE_TTL);
        } catch (Exception e) {
            log.debug("写入 LLM 缓存失败, history={}", history, e);
        }
    }

    /**
     * 生成 LLM 缓存 key，对齐 RagFlow：xxh64(str(llmnm) + str(txt) + str(history) + str(genconf)).hexdigest()。
     * llmName 无跨语言共享需求，使用固定标识以在本项目内自洽。
     */
    private String llmCacheKey(String txt, String history, String genconf) {
        String base = LLM_CACHE_MODEL_NAME + txt + history + genconf;
        long h = LongHashFunction.xx().hashBytes(base.getBytes(StandardCharsets.UTF_8));
        return String.format("%016x", h);
    }

    /**
     * embedding：为每个分块生成向量并挂到 q_{dim}_vec 字段。
     * 严格对齐 RagFlow embedding()：
     * <ol>
     *   <li>title 取 docnm_kwd，content 优先取 question_kwd（换行拼接），否则取 content_with_weight；</li>
     *   <li>content 剥离 table/td/caption/tr/th 等表格 HTML 标签，空白内容用 "None" 占位；</li>
     *   <li>title 单独编码一次并复用到所有分块（对应 Python np.tile）；</li>
     *   <li>content 按 EMBEDDING_BATCH_SIZE 分批编码，逐批累加真实 token 并回调进度
     *       0.7 + 0.2 * (i+1)/len（对应 Python for-range 分批 + callback）；</li>
     *   <li>最终向量 = title_w * title_vec + (1 - title_w) * content_vec，
     *       仅当 title 与 content 向量维度一致时加权，否则退回纯 content 向量
     *       （对应 Python tts.shape == cnts.shape 判定）；
     *       title_w 取 parser_config.filename_embd_weight（默认 0.1）。</li>
     * </ol>
     *
     * @return 真实 token 总数与向量维度（对应 RagFlow return tk_count, vector_size）
     */
    private EmbeddingResult embedding(DocTaskMessage msg, List<Map<String, Object>> chunks) {
        // title：对应 RagFlow d.get("docnm_kwd", "Title")
        List<String> titles = new ArrayList<>(chunks.size());
        // content：对应 RagFlow 优先 question_kwd 换行拼接，否则 content_with_weight，再剥离表格标签
        List<String> contents = new ArrayList<>(chunks.size());
        for (Map<String, Object> ck : chunks) {
            Object docNm = ck.get(FIELD_DOC_NAME);
            titles.add(docNm == null ? "Title" : String.valueOf(docNm));

            String c = questionOrContent(ck);
            // 剥离表格 HTML 标签（对应 RagFlow re.sub(r"</?(table|td|caption|tr|th)...>", " ", c)）
            c = c.replaceAll(TABLE_TAG_REGEX, " ");
            // 空白内容用占位符，避免嵌入模型收到空输入（对应 RagFlow "None" 占位）
            contents.add(StringUtils.isBlank(c) ? EMPTY_CONTENT_PLACEHOLDER : c);
        }

        int tkCount = 0;

        // title 只编码第一个并复用到所有分块（对应 RagFlow：
        // if len(tts) == len(cnts): vts,c = mdl.encode(tts[0:1]); tts = np.tile(vts[0], (len(cnts),1)); tk_count += c）
        // titles 与 contents 长度恒等，故与 Python 一致地始终执行 title 复用分支。
        float[] titleVec;
        EmbeddingResponse titleResp = embeddingModel.call(new EmbeddingRequest(List.of(titles.get(0)), null));
        titleVec = titleResp.getResults().get(0).getOutput();
        tkCount += usageTokens(titleResp);

        // content 分批编码（对应 RagFlow：for i in range(0, len(cnts), EMBEDDING_BATCH_SIZE): mdl.encode(cnts[i:i+B])）。
        // 逐批累加真实 token，并在每批结束后回调进度 0.7 + 0.2 * (i+1)/len（对应 Python callback）。
        List<float[]> contentVecs = new ArrayList<>(contents.size());
        for (int i = 0; i < contents.size(); i += EMBEDDING_BATCH_SIZE) {
            if (hasCanceled(msg.getTaskId())) {
                markProgress(msg, PROGRESS_FAIL, "Task has been canceled.");
                throw new IllegalStateException("Task has been canceled.");
            }
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, contents.size());
            List<String> batch = contents.subList(i, end);
            EmbeddingResponse resp = embeddingModel.call(new EmbeddingRequest(new ArrayList<>(batch), null));
            for (int j = 0; j < resp.getResults().size(); j++) {
                contentVecs.add(resp.getResults().get(j).getOutput());
            }
            tkCount += usageTokens(resp);
            // 对应 RagFlow：callback(prog=0.7 + 0.2 * (i+1)/len(cnts))
            double prog = EMBEDDING_PROGRESS_BASE + EMBEDDING_PROGRESS_SPAN * end / contents.size();
            markProgress(msg, BigDecimal.valueOf(prog), "");
        }

        // 对应 RagFlow assert len(vects) == len(docs)
        if (contentVecs.size() != chunks.size()) {
            throw new IllegalStateException(
                    "向量数量与分块数量不一致: " + contentVecs.size() + " != " + chunks.size());
        }

        // title 权重（对应 RagFlow filename_embd_weight，为空/0 时回退 0.1）
        Map<String, Object> parserConfig = parseParserConfig(msg);
        double titleWeight = getDouble(parserConfig.get("filename_embd_weight"), DEFAULT_FILENAME_EMBD_WEIGHT);
        if (titleWeight <= 0) {
            titleWeight = DEFAULT_FILENAME_EMBD_WEIGHT;
        }

        // 加权组合条件对齐 RagFlow：仅当 title 与 content 向量维度一致（tts.shape == cnts.shape）才加权，
        // 否则 vects = cnts（退回纯 content 向量）。
        int vectorSize = 0;
        for (int i = 0; i < chunks.size(); i++) {
            float[] contentVec = contentVecs.get(i);
            List<Float> vecList = new ArrayList<>(contentVec.length);
            if (titleVec.length == contentVec.length) {
                for (int j = 0; j < contentVec.length; j++) {
                    vecList.add((float) (titleWeight * titleVec[j] + (1 - titleWeight) * contentVec[j]));
                }
            } else {
                for (float v : contentVec) {
                    vecList.add(v);
                }
            }
            // 向量维度由实际编码结果决定（对应 RagFlow vector_size = len(v)），字段名 q_{dim}_vec
            vectorSize = vecList.size();
            chunks.get(i).put(VECTOR_FIELD_PREFIX + vectorSize + VECTOR_FIELD_SUFFIX, vecList);
        }

        markProgress(msg, PROGRESS_EMBEDDED, "向量化完成");
        // 对应 RagFlow：return tk_count, vector_size
        return new EmbeddingResult(tkCount, vectorSize);
    }

    /**
     * 从嵌入响应中读取真实 token 消耗，对应 RagFlow mdl.encode 返回的 token 计数（tk_count += c）。
     * 无 usage 信息时返回 0，避免统计中断。
     */
    private int usageTokens(EmbeddingResponse resp) {
    try {
            if (resp != null && resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                Integer total = resp.getMetadata().getUsage().getTotalTokens();
                return total == null ? 0 : total;
            }
        } catch (Exception e) {
            log.debug("读取 embedding token usage 失败", e);
        }
        return 0;
    }

    /**
     * embedding 结果载体，对应 RagFlow embedding() 返回的 (tk_count, vector_size)。
     */
    private record EmbeddingResult(int tokenCount, int vectorSize) {
    }

    /**
     * 取分块用于向量化的正文：优先 question_kwd 换行拼接，否则 content_with_weight。
     * 对应 RagFlow embedding()：c = "\n".join(d.get("question_kwd", [])); if not c: c = d["content_with_weight"]。
     */
    @SuppressWarnings("unchecked")
    private String questionOrContent(Map<String, Object> ck) {
        Object questions = ck.get(FIELD_QUESTION_KWD);
        if (questions instanceof List<?> list && !list.isEmpty()) {
            List<String> qs = new ArrayList<>(list.size());
            for (Object q : (List<Object>) list) {
                if (q != null) {
                    qs.add(String.valueOf(q));
                }
            }
            String joined = String.join("\n", qs);
            if (StringUtils.isNotBlank(joined)) {
                return joined;
            }
        }
        return String.valueOf(ck.get(FIELD_CONTENT));
    }

    /**
     * 读取浮点配置值（兼容 Number / String），非法或为空返回默认值。
     */
    private double getDouble(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /**
     * insertChunks：按 DOC_BULK_SIZE 批量写入 ES，并回填 task 的 chunk_ids。
     * 对应 RagFlow insert_chunks() -> docStoreConn.insert + TaskService.update_chunk_ids。
     */
    private void insertChunks(DocTaskMessage msg, List<Map<String, Object>> chunks) {
        String index = DocTaskConstants.indexName(msg.getUserId());
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
        taskUpdate.setId(msg.getTaskId());
        taskUpdate.setChunkIds(String.join(" ", allChunkIds));
        kbTaskCoreService.updateById(taskUpdate);
    }

    /**
     * 回写任务进度（对应 RagFlow set_progress -> TaskService.update_progress）。
     * <p>同步更新 task 与 document 的 progress / progress_msg，便于前端轮询展示。</p>
     */
    private void markProgress(DocTaskMessage msg, BigDecimal progress, String message) {
        KbTaskPO task = new KbTaskPO();
        task.setId(msg.getTaskId());
        task.setProgress(progress);
        task.setProgressMsg(message);
        kbTaskCoreService.updateById(task);
    }

    /**
     * 解析成功后回写文档统计与状态（对应 RagFlow increment_chunk_num + run=DONE + progress=1）。
     * <p>严格对齐 Python increment_chunk_num：chunk/token 采用<strong>原子累加</strong>语义，
     * 并同步累加所属知识库的 chunk_num / token_num（Python 在同一事务里同时更新 document 与 knowledgebase）。
     * ingest 重跑前已清零文档统计，因此累加结果即为最终值，且天然支持一个文档拆分为多个 task 的场景。</p>
     */
    private void finishDoc(DocTaskMessage msg, int chunkCount, int tokenCount) {
        Long docId = msg.getDocId();
        // 文档：run=DONE、progress=1，chunk/token 原子累加（对应 cls.model.chunk_num + chunk_num）
        LambdaUpdateWrapper<KbDocumentPO> docUpdate = new LambdaUpdateWrapper<KbDocumentPO>()
                .eq(KbDocumentPO::getId, docId)
                .set(KbDocumentPO::getRun, TaskStatusEnum.DONE.getValue())
                .setSql("chunk_count = IFNULL(chunk_count, 0) + " + chunkCount)
                .setSql("token_count = IFNULL(token_count, 0) + " + tokenCount);
        kbDocumentCoreService.update(docUpdate);

        // 知识库：chunk_num / token_num 原子累加（对应 Knowledgebase.update(chunk_num + chunk_num)）
        if (msg.getKbId() != null) {
            LambdaUpdateWrapper<KnowledgeBasePO> kbUpdate = new LambdaUpdateWrapper<KnowledgeBasePO>()
                    .eq(KnowledgeBasePO::getId, msg.getKbId())
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
            doc.setId(msg.getDocId());
            doc.setRun(TaskStatusEnum.FAIL.getValue());
            doc.setErrorMsg(errorMsg);
            kbDocumentCoreService.updateById(doc);

            KbTaskPO task = new KbTaskPO();
            task.setId(msg.getTaskId());
            task.setProgress(PROGRESS_FAIL);
            task.setProgressMsg(errorMsg);
            kbTaskCoreService.updateById(task);
        } catch (Exception e) {
            log.warn("回写失败状态异常, taskId={}, docId={}", msg.getTaskId(), msg.getDocId(), e);
        }
    }
}