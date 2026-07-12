package com.example.dream.service.core.chat.impl;

import com.example.dream.dal.po.KnowledgeBasePO;
import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.service.biz.bo.chat.DialogBO;
import com.example.dream.service.core.KnowledgeBaseCoreService;
import com.example.dream.service.core.chat.AsyncChatService;
import com.example.dream.service.core.chat.retriever.Retriever;
import com.example.dream.common.constant.DocTaskConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link AsyncChatService} 实现，严格 1:1 还原 RagFlow dialog_service.async_chat 的编排逻辑。
 *
 * <p>对齐 Python 源文件 api/db/services/dialog_service.py 中的 async_chat（含 async_chat_solo、
 * decorate_answer、_stream_with_think_delta 等）。RagFlow 特有的底层能力（模型配置解析、
 * 向量检索 retrieval / rerank、insert_citations 引用注入、DeepResearcher 深度检索、Tavily
 * 网搜、KG 图谱检索、use_sql、TTS、langfuse、多模态附件、full_question / keyword_extraction /
 * cross_languages 等）在当前项目中尚不具备，统一以 TODO 桩方法占位，保证分支结构与 Python 完全一致
 * 且可编译。</p>
 *
 * <p>产出协议：流式模式下逐块回调增量 answer，并最终回调一个 final=true 结果；
 * 非流式模式下仅回调一次完整结果。回调等价于 Python async generator 的逐次 yield。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncChatServiceImpl implements AsyncChatService {

    /**
     * Spring AI 2.0 自动装配的聊天模型（由 spring-ai-starter-model-openai 提供，
     * 读取 application.yml 中 spring.ai.openai.chat 配置）。
     */
    private final ChatModel chatModel;

    /**
     * Spring AI 2.0 自动装配的嵌入模型（由 spring-ai-starter-model-openai 提供，
     * 读取 application.yml 中 spring.ai.openai.embedding 配置）。
     */
    private final EmbeddingModel embeddingModel;

    private final KnowledgeBaseCoreService knowledgeBaseCoreService;

    /**
     * 检索器（对应 RagFlow settings.retriever / Dealer），是引擎无关的检索编排层契约。
     * Spring 注入其唯一实现
     * {@link com.example.dream.service.core.chat.retriever.Dealer Dealer}，
     * 底层存储 I/O 经 DocStoreConnection 抽象隔离（当前为 ElasticsearchDocStore）。
     */
    private final Retriever retriever;

    /**
     * 引用坏格式修复正则（对应 Python BAD_CITATION_PATTERNS）。
     */
    private static final List<Pattern> BAD_CITATION_PATTERNS = List.of(
            Pattern.compile("\\(\\s*ID\\s*[: ]*\\s*(\\d+)\\s*\\)"),
            Pattern.compile("\\[\\s*ID\\s*[: ]*\\s*(\\d+)\\s*\\]"),
            Pattern.compile("【\\s*ID\\s*[: ]*\\s*(\\d+)\\s*】"),
            Pattern.compile("ref\\s*(\\d+)", Pattern.CASE_INSENSITIVE));

    /**
     * 引用标记正则（对应 Python CITATION_MARKER_PATTERN）。
     */
    private static final Pattern CITATION_MARKER_PATTERN =
            Pattern.compile("\\[(?:ID:)?([0-9]+)\\]");

    /**
     * 去除 ##数字$$ 标记的正则（对应 Python re.sub(r"##\d+\$\$", "", ...)）。
     */
    private static final Pattern DOC_MARKER_PATTERN = Pattern.compile("##\\d+\\$\\$");

    /**
     * 提示词模板缓存（对应 RagFlow rag.prompts.template._loaded_prompts）。
     */
    private static final Map<String, String> PROMPT_CACHE = new ConcurrentHashMap<>();

    /**
     * 执行一轮对话生成（对应 Python: async def async_chat(dialog, messages, stream=True, **kwargs)）。
     * 实现了一个功能极其完整、生产级别的 RAG（Retrieval-Augmented Generation，检索增强生成）系统的核心对话流。
     * 它支持混合检索（SQL + 向量 + 知识图谱 + 全文）、
     * 多轮对话改写、跨语言检索、Deep Research（深度研究/Agent模式）、自动化引用生成（Citation） 以及完整的 Langfuse 链路追踪与耗时统计。
     */
    @Override
    public void asyncChat(DialogBO dialog,
                          List<ChatMessageBO> messages,
                          boolean stream,
                          Long convId,
                          Map<String, Object> extraParams,
                          Consumer<ChatAnswerBO> chunkConsumer) {
        if (chunkConsumer == null) {
            return;
        }

        Map<String, Object> kwargs = extraParams == null ? new HashMap<>() : new HashMap<>(extraParams);

        log.debug("Begin async_chat");
        // assert messages[-1]["role"] == "user"
        if (CollectionUtils.isEmpty(messages)
                || !"user".equals(messages.getLast().getRole())) {
            throw new IllegalArgumentException("The last content of this conversation is not from user.");
        }

        // if not dialog.kb_ids and not use_web_search: -> async_chat_solo
        if (CollectionUtils.isEmpty(dialog.getKbIds())) {
            throw new IllegalArgumentException("rag 对话必须要有知识库id，没有知识库id的单纯对话我后续是要做的，现在是学rag.");
        }

        long chatStartTs = System.nanoTime();

        List<Long> kbIds = dialog.getKbIds();
        List<KnowledgeBasePO> kbList = knowledgeBaseCoreService.listByIds(kbIds);
        Object rerankModel = null;
        // max_tokens = llm_model_config.get("max_tokens") or 8192
        int maxTokens =  8192;

        long checkLlmTs = System.nanoTime();

        // questions = [m["content"] for m in messages if role == "user"][-3:]
        // 多轮问题截取： 获取用户最后 3 轮的对话内容，用于后续的多轮理解。
        List<String> questions = new ArrayList<>();
        for (ChatMessageBO m : messages) {
            if ("user".equals(m.getRole())) {
                questions.add(m.getContent());
            }
        }
        questions = lastN(questions, 3);

        // attachments（doc_ids）
        List<Long> attachments = null;

        String attachmentsText = "";
        List<String> imageAttachments = new ArrayList<>();
        List<Object> imageFiles = new ArrayList<>();

        Map<String, Object> promptConfig = dialog.getPromptConfig() == null
                ? new HashMap<>() : new HashMap<>(dialog.getPromptConfig());

        // 对应 Python: include_metadata, metadata_fields = _resolve_reference_metadata_preferences(kwargs, prompt_config)
        // request 取 kwargs（本次请求入参），config 取 prompt_config（对话配置），request 值优先于 config。
        ReferenceMetadataPreference referenceMetadataPreference =
                resolveReferenceMetadataPreferences(kwargs, promptConfig);
        boolean includeReferenceMetadata = referenceMetadataPreference.include;
        Set<String> metadataFields = referenceMetadataPreference.fields;

        // 🔀 模块二：精准 SQL 检索（Fast Path）
        // 在进入复杂的向量检索前，系统会判断是否能通过结构化 SQL 直接获取精准答案。
        // 逻辑机制： 如果知识库配置了良好的字段映射（field_map），系统优先将用户问题转化为 SQL 语句去查结构化数据库。
        // 命中即返回： 如果 SQL 查到了确切的聚合结果（如 COUNT、SUM）或对应的文档分块（chunks），则直接通过 yield ans 吐出结果并中断后续流程。
        // 这是非常优秀的工程优化（降低延迟、提高准确率）。
        // 我直接删了，后续要学再去看
        // if field_map:
        //    logging.debug("Use SQL to retrieval:{}".format(questions[-1]))


        // parameters 处理
        List<String> paramKeys = collectParamKeys(promptConfig);
        String systemTpl = strOf(promptConfig.get("system"));
        // 1.dialog.kb_ids 存在（意味着当前对话关联了知识库）。
        // 2."knowledge" 不在刚才提取的参数列表里（配置遗漏了）。
        // 3.系统提示词 system 中包含了 "{knowledge}" 占位符。
        // 处理逻辑：如果满足以上条件，说明配置写错了（有知识库却没声明参数）。代码会打印警告日志，并动态地把 knowledge 参数追加到配置中，标记为非必填/必填（这里设为了 optional: False 必填），同时更新 param_keys。
        if (!CollectionUtils.isEmpty(dialog.getKbIds())
                && !paramKeys.contains("knowledge") && systemTpl.contains("{knowledge}")) {
            log.warn("prompt_config['parameters'] is missing 'knowledge' entry despite kb_ids being set; auto-fixing.");
            addKnowledgeParam(promptConfig);
            paramKeys.add("knowledge");
        }

        // 4. 循环检查参数的完整性：遍历所有定义的参数。
        for (Map<String, Object> p : parametersOf(promptConfig)) {
            String key = strOf(p.get("key"));
            // 如果是 "knowledge" 参数则跳过不处理（因为知识库通常由系统后台自动注入，不需要用户在 kwargs 中手动传参）。
            if ("knowledge".equals(key)) {
                continue;
            }
            // 5. 强校验：缺少必填参数时报错
            // 如果一个参数没有在用户传入的实参 kwargs 中，且该参数在配置中被明确标记为（optional: False，即必填），程序会直接抛出 KeyError 异常，阻止代码继续执行。
            boolean optional = getBool(p.get("optional"), false);
            if (!kwargs.containsKey(key) && !optional) {
                throw new IllegalArgumentException("Miss parameter: " + key);
            }
            // 6. 弱处理：可选参数未传时清空占位符
            // 如果一个参数没有在 kwargs 中（走到这一步说明它是 optional: True 可选的），为了防止系统提示词里留下形如 {user_hobby} 这样的裸字符串，代码会自动把提示词中的占位符替换为空格 " "。
            if (!kwargs.containsKey(key)) {
                systemTpl = systemTpl.replace("{" + key + "}", " ");
                promptConfig.put("system", systemTpl);
            }
        }

        // 🔧 模块三：Query（问题）预处理与改写：当无法走 SQL 捷径时，系统开始对用户的输入进行“微调”和“丰富”。
        // full_question / cross_languages / keyword
        // 条件1：len(questions) > 1 表示当前对话存在多轮历史记录（不只有用户刚刚发送的那一条）。
        // 条件2：prompt_config.get("refine_multiturn") 检查配置中是否启用了“多轮对话重写/精炼”功能。
        // 如果上述两个条件都满足 代码会调用一个异步函数 full_question。
        // 作用：它会把当前对话的上下文（messages）传给 LLM，让 LLM 把带有指代消解（比如“它”、“那个”）或省略的最新问题，重写为一个独立、完整的、没有歧义的问题。
        if (questions.size() > 1 && getBool(promptConfig.get("refine_multiturn"), false)) {
            questions = new ArrayList<>(List.of(
                    fullQuestion(messages)));
        } else {
            // 如果不满足条件（例如：关闭了精炼功能，或者只有一轮对话）。
            // 作用：切片操作 [-1:] 表示只保留最后一条问题（即用户最新输入的那句话），直接丢弃之前的历史问题。
            questions = lastN(questions, 1);
        }

        // 跨语言翻译处理 todo：还空着在，需要补充
        // 作用：将上面处理完的目标问题（questions[0]），翻译成配置中指定的语言（prompt_config["cross_languages"]）。
        // 例如，将中文问题翻译成英文再提交给只懂英文的底层知识库或模型。
        if (promptConfig.get("cross_languages") != null) {
            questions = new ArrayList<>(List.of(
                    crossLanguages(dialog.getUserId(), dialog.getLlmId(),
                            questions.getFirst(), promptConfig.get("cross_languages"))));
        }

        // meta_data_filter（暂未实现，桩占位）
        if (dialog.getMetaDataFilter() != null && !dialog.getMetaDataFilter().isEmpty()) {
            attachments = applyMetaDataFilter(dialog.getMetaDataFilter(),
                    questions.getLast(), chatModel, attachments, dialog.getKbIds());
        }

        // 关键次扩展：通过 keyword_extraction 提取核心词追加到问题末尾，增强召回。
        if (getBool(promptConfig.get("keyword"), false)) {
            String last = questions.getLast();
            // List<String>的set语法：更新 questions 列表（List）中的最后一项，在其原有内容后面追加通过模型提取出来的关键词。
            questions.set(questions.size() - 1,
                    last + "," + keywordExtraction(chatModel, last));
        }
        long refineQuestionTs = System.nanoTime();

        // ====== 检索阶段 ======
        // 🔍 模块四：多路混合检索（Hybrid Retrieval）与 Agent 模式
        // 这是代码的核心检索部分，分支为 Agent 深度搜索 和 常规多路召回：
        String thought = "";
        Map<String, Object> kbinfos = newKbinfos();
        List<String> knowledges;

        if (paramKeys.contains("knowledge")) {
            log.debug("Proceeding with retrieval");
            List<String> userIds = userIdsOf(kbList);
            // 分支 A：Deep Research 模式（Agent 模式）
            if (getBool(promptConfig.get("reasoning"), false) || getBool(kwargs.get("reasoning"), false)) {
                // DeepResearcher 深度检索（桩占位，仍逐条 yield 检索过程标记）
                deepResearch(chatModel, promptConfig, embeddingModel, userIds, dialog,
                        attachments, kbinfos, questions, chunkConsumer);
            }
            // 分支 B：常规多路召回（向量 + 树状上下级 + 网络 + 知识图谱）
            else {

                if (embeddingModel != null) {
                    kbinfos = retriever.retrieval(
                            String.join(" ", questions),
                            embeddingModel,
                            userIds,
                            dialog.getKbIds(),
                            1,
                            dialog.getTopN(),
                            dialog.getSimilarityThreshold(),
                            dialog.getVectorSimilarityWeight(),
                            attachments,
                            dialog.getTopK(),
                            true,
                            rerankModel,
                            labelQuestion(String.join(" ", questions), kbList));
                    if (getBool(promptConfig.get("toc_enhance"), false)) {
                        List<Map<String, Object>> cks = retrievalByToc(retriever,
                                String.join(" ", questions), chunksOf(kbinfos), userIds,
                                chatModel, dialog.getTopN());
                        if (cks != null && !cks.isEmpty()) {
                            kbinfos.put("chunks", cks);
                        }
                    }
                    kbinfos.put("chunks", retrievalByChildren(retriever, chunksOf(kbinfos), userIds));
                }
                if (getBool(promptConfig.get("use_kg"), false)) {
                    Map<String, Object> ck = kgRetrieval(String.join(" ", questions), userIds,
                            dialog.getKbIds(), embeddingModel, dialog.getUserId(), convId);
                    if (ck != null && StringUtils.isNotBlank(strOf(ck.get("content_with_weight")))) {
                        chunksOf(kbinfos).add(0, ck);
                    }
                }
            }
        }

        if (includeReferenceMetadata) {
            log.debug("reference_metadata enrichment enabled for async_chat: chunk_count={} metadata_fields={}",
                    chunksOf(kbinfos).size(), metadataFields);
            enrichChunksWithDocumentMetadata(chunksOf(kbinfos), metadataFields);
        }

        knowledges = kbPrompt(kbinfos, maxTokens);
        log.debug("{}->{}", String.join(" ", questions), String.join("\n->", knowledges));

        long retrievalTs = System.nanoTime();
        if (knowledges.isEmpty() && promptConfig.get("empty_response") != null) {
            String emptyRes = strOf(promptConfig.get("empty_response"));
            ChatAnswerBO ans = new ChatAnswerBO();
            ans.setAnswer(emptyRes);
            ans.setReference(kbinfos);
            ans.setFinalFlag(Boolean.TRUE);
            putExtra(ans, "prompt", "\n\n### Query:\n" + String.join(" ", questions));
            chunkConsumer.accept(ans);
            return;
        }

        // knowledge 拼接
        String knowledgeText = String.join("\n\n------\n\n", knowledges);
        if (StringUtils.isNotBlank(knowledgeText)) {
            kwargs.put("knowledge", "\n------\n" + knowledgeText);
        } else {
            kwargs.putIfAbsent("knowledge", "");
        }
        Map<String, Object> genConf = dialog.getLlmSetting() == null
                ? new HashMap<>() : new HashMap<>(dialog.getLlmSetting());

        String systemContent = formatTemplate(strOf(promptConfig.get("system")), kwargs) + attachmentsText;
        if (!knowledges.isEmpty() && !strOf(promptConfig.get("system")).contains("{knowledge}")) {
            systemContent += strOf(kwargs.get("knowledge"));
        }
        List<Map<String, Object>> msg = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent);
        msg.add(systemMsg);

        String prompt4citation = "";
        if (!knowledges.isEmpty()
                && getBool(promptConfig.get("quote"), true) && getBool(kwargs.get("quote"), true)) {
            prompt4citation = citationPrompt();
        }
        for (ChatMessageBO m : messages) {
            if ("system".equals(m.getRole())) {
                continue;
            }
            Map<String, Object> mm = new HashMap<>();
            mm.put("role", m.getRole());
            mm.put("content", DOC_MARKER_PATTERN.matcher(m.getContent() == null ? "" : m.getContent()).replaceAll(""));
            msg.add(mm);
        }
        // message_fit_in
        FitInResult fitIn = messageFitIn(msg, (int) (maxTokens * 0.95));
        int usedTokenCount = fitIn.usedTokenCount;
        msg = fitIn.msg;

        // 删掉了 convert_last_user_msg_to_multimodal
        // 将多轮对话历史（msg）中“最后一条由用户发送的消息”转换为多模态结构，即把一组图片的 Data URI（或 Base64 字符串）合并到该消息的 content 中。

        if (msg.size() < 2) {
            throw new IllegalStateException("message_fit_in has bug: " + msg);
        }
        String prompt = strOf(msg.get(0).get("content"));

        if (genConf.containsKey("max_tokens")) {
            int gc = ((Number) genConf.get("max_tokens")).intValue();
            genConf.put("max_tokens", Math.min(gc, maxTokens - usedTokenCount));
        }

        // decorate_answer 所需的上下文，通过 final 引用捕获
        DecorateContext dc = new DecorateContext();
        dc.embdMdl = embeddingModel;
        dc.promptConfig = promptConfig;
        dc.knowledges = knowledges;
        dc.kwargs = kwargs;
        dc.kbinfos = kbinfos;
        dc.retriever = retriever;
        dc.userIds = userIdsOf(kbList);
        dc.dialog = dialog;
        dc.questions = questions;
        dc.prompt = prompt;
        dc.chatStartTs = chatStartTs;
        dc.checkLlmTs = checkLlmTs;
        dc.refineQuestionTs = refineQuestionTs;
        dc.retrievalTs = retrievalTs;
        dc.usedTokenCount = usedTokenCount;


        List<Map<String, Object>> msgWithoutSystem = msg.subList(1, msg.size());
        if (stream) {
            ThinkStreamState lastState = streamlyDelta(chatModel, prompt + prompt4citation, msgWithoutSystem, genConf, null, chunkConsumer, rerankModel);
            String fullAnswer = lastState == null ? "" : lastState.fullText;
            if (StringUtils.isNotBlank(fullAnswer)) {
                ChatAnswerBO fin = decorateAnswer(extractVisibleAnswer(thought + fullAnswer), dc);
                fin.setFinalFlag(Boolean.TRUE);
                fin.setAnswer("");
                chunkConsumer.accept(fin);
            }
        } else {
            String answer = asyncChatCall(chatModel, prompt + prompt4citation, msgWithoutSystem);
            Object userContent = msg.getLast().getOrDefault("content", "[content not available]");
            log.debug("User: {}|Assistant: {}", userContent, answer);
            ChatAnswerBO res = decorateAnswer(answer, dc);
            chunkConsumer.accept(res);
        }
    }

    // ==================== decorate_answer ====================

    /**
     * 组装最终答案与引用（对应 Python: async def decorate_answer(answer)）。
     */
    private ChatAnswerBO decorateAnswer(String answer, DecorateContext dc) {
        List<Object> refs = new ArrayList<>();
        String think = "";
        String[] ans = answer.split("</think>", 2);
        if (ans.length == 2) {
            think = ans[0] + "</think>";
            answer = ans[1];
        }

        boolean quote = getBool(dc.promptConfig.get("quote"), true) && getBool(dc.kwargs.get("quote"), true);
        Map<String, Object> kbinfos = dc.kbinfos;
        Set<Integer> idx = new LinkedHashSet<>();
        Map<String, Object> refsMap = null;

        if (!dc.knowledges.isEmpty() && quote) {
            String normalizedAnswer = normalizeArabicDigits(answer);
            if (dc.embdMdl != null && !CITATION_MARKER_PATTERN.matcher(normalizedAnswer).find()) {
                // 拉取待引用 chunk 的向量后做 insert_citations（桩占位）
                hydrateChunkVectors(dc.retriever, chunksOf(kbinfos), dc.userIds, dc.dialog.getKbIds());
                InsertCitationsResult ir = insertCitations(dc.retriever, answer,
                        contentLtksOf(kbinfos), vectorsOf(kbinfos), dc.embdMdl,
                        1 - dc.dialog.getVectorSimilarityWeight(), dc.dialog.getVectorSimilarityWeight());
                answer = ir.answer;
                idx = ir.idx;
            } else {
                Matcher matcher = CITATION_MARKER_PATTERN.matcher(normalizedAnswer);
                while (matcher.find()) {
                    int i = Integer.parseInt(matcher.group(1));
                    if (i < chunksOf(kbinfos).size()) {
                        idx.add(i);
                    }
                }
            }

            RepairResult rr = repairBadCitationFormats(answer, kbinfos, idx);
            answer = rr.answer;
            idx = rr.idx;

            // idx = set([chunks[i]["doc_id"] for i in idx])
            Set<Object> docIds = new LinkedHashSet<>();
            List<Map<String, Object>> chunks = chunksOf(kbinfos);
            for (Integer i : idx) {
                docIds.add(chunks.get(i).get("doc_id"));
            }
            List<Map<String, Object>> recallDocs = new ArrayList<>();
            for (Map<String, Object> d : docAggsOf(kbinfos)) {
                if (docIds.contains(d.get("doc_id"))) {
                    recallDocs.add(d);
                }
            }
            if (recallDocs.isEmpty()) {
                recallDocs = docAggsOf(kbinfos);
            }
            kbinfos.put("doc_aggs", recallDocs);

            refsMap = deepCopyKbinfos(kbinfos);
            for (Map<String, Object> c : chunksOf(refsMap)) {
                c.remove("vector");
            }
        }

        if (answer.toLowerCase().contains("invalid key") || answer.toLowerCase().contains("invalid api")) {
            answer += " Please set LLM API-Key in 'User Setting -> Model providers -> API-Key'";
        }
        long finishChatTs = System.nanoTime();

        double totalTimeCost = (finishChatTs - dc.chatStartTs) / 1_000_000.0;
        double checkLlmTimeCost = (dc.checkLlmTs - dc.chatStartTs) / 1_000_000.0;
        double checkLangfuseTracerCost = (dc.checkLangfuseTracerTs - dc.checkLlmTs) / 1_000_000.0;
        double bindEmbeddingTimeCost = (dc.bindModelsTs - dc.checkLangfuseTracerTs) / 1_000_000.0;
        double refineQuestionTimeCost = (dc.refineQuestionTs - dc.bindModelsTs) / 1_000_000.0;
        double retrievalTimeCost = (dc.retrievalTs - dc.refineQuestionTs) / 1_000_000.0;
        double generateResultTimeCost = (finishChatTs - dc.retrievalTs) / 1_000_000.0;

        int tkNum = numTokensFromString(think + answer);
        String prompt = dc.prompt + "\n\n### Query:\n" + String.join(" ", dc.questions);
        int tokenSpeed = generateResultTimeCost <= 0 ? 0 : (int) (tkNum / (generateResultTimeCost / 1000.0));
        prompt = prompt + "\n\n"
                + "## Time elapsed:\n"
                + String.format("  - Total: %.1fms%n", totalTimeCost)
                + String.format("  - Check LLM: %.1fms%n", checkLlmTimeCost)
                + String.format("  - Check Langfuse tracer: %.1fms%n", checkLangfuseTracerCost)
                + String.format("  - Bind models: %.1fms%n", bindEmbeddingTimeCost)
                + String.format("  - Query refinement(LLM): %.1fms%n", refineQuestionTimeCost)
                + String.format("  - Retrieval: %.1fms%n", retrievalTimeCost)
                + String.format("  - Generate answer: %.1fms%n%n", generateResultTimeCost)
                + "## Token usage:\n"
                + String.format("  - Generated tokens(approximately): %d%n", tkNum)
                + String.format("  - Token speed: %d/s", tokenSpeed);

        // langfuse_generation.update / end（桩占位）
        if (dc.langfuseGeneration != null) {
            endLangfuseObservation(dc.langfuseGeneration, prompt, dc.usedTokenCount, tkNum);
        }

        ChatAnswerBO result = new ChatAnswerBO();
        result.setAnswer(think + answer);
        result.setReference(refsMap != null ? refsMap : new HashMap<>());
        putExtra(result, "prompt", prompt.replace("\n", "  \n"));
        putExtra(result, "created_at", System.currentTimeMillis() / 1000.0);
        return result;
    }

    // ==================== 纯逻辑工具（1:1 还原，非桩） ====================

    /**
     * 对应 Python _should_use_web_search。
     */
    private boolean shouldUseWebSearch(Map<String, Object> promptConfig, Object internet) {
        if (promptConfig == null || promptConfig.get("tavily_api_key") == null
                || !StringUtils.isNotBlank(strOf(promptConfig.get("tavily_api_key")))) {
            return false;
        }
        Boolean normalized = normalizeInternetFlag(internet);
        return Boolean.TRUE.equals(normalized);
    }

    /**
     * 对应 Python _normalize_internet_flag。
     */
    private Boolean normalizeInternetFlag(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            int i = n.intValue();
            if (i == 0 || i == 1) {
                return i == 1;
            }
        }
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase();
            if (Set.of("true", "1", "yes", "on").contains(normalized)) {
                return Boolean.TRUE;
            }
            if (Set.of("false", "0", "no", "off", "").contains(normalized)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * 对应 Python repair_bad_citation_formats。
     */
    private RepairResult repairBadCitationFormats(String answer, Map<String, Object> kbinfos, Set<Integer> idx) {
        int maxIndex = chunksOf(kbinfos).size();
        String working = answer;
        for (Pattern pattern : BAD_CITATION_PATTERNS) {
            String normalized = normalizeArabicDigits(working);
            Matcher matcher = pattern.matcher(normalized);
            if (!matcher.find()) {
                continue;
            }
            matcher.reset();
            StringBuilder sb = new StringBuilder();
            int last = 0;
            while (matcher.find()) {
                sb.append(working, last, matcher.start());
                int i;
                try {
                    i = Integer.parseInt(matcher.group(1));
                } catch (Exception e) {
                    sb.append(working, matcher.start(), matcher.end());
                    last = matcher.end();
                    continue;
                }
                if (i >= 0 && i < maxIndex) {
                    idx.add(i);
                    sb.append("[ID:").append(matcher.group(1)).append("]");
                } else {
                    sb.append(working, matcher.start(), matcher.end());
                }
                last = matcher.end();
            }
            sb.append(working.substring(last));
            working = sb.toString();
        }
        return new RepairResult(working, idx);
    }

    /**
     * 对应 Python _extract_visible_answer。
     */
    private String extractVisibleAnswer(String text) {
        text = text == null ? "" : text;
        if (!text.contains("</think>")) {
            return text.replaceAll("</?think>", "");
        }
        int splitIdx = text.lastIndexOf("</think>");
        String thought = text.substring(0, splitIdx);
        String answer = text.substring(splitIdx + "</think>".length());
        thought = thought.replaceAll("</?think>", "").strip();
        answer = answer.replaceAll("</?think>", "");
        if (thought.isEmpty()) {
            return answer;
        }
        return "<think>" + thought + "</think>" + answer;
    }

    /**
     * 对应 Python str.format(**kwargs) 的最简实现：将 {key} 替换为 kwargs 中对应值。
     */
    private String formatTemplate(String template, Map<String, Object> kwargs) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, Object> e : kwargs.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return result;
    }

    // ==================== 以下为 TODO 桩方法：待接入真实底层能力 ====================


    /**
     * TODO 对应 LLMBundle 构造：创建聊天/嵌入/TTS 模型包装。
     */
    private Object newLlmBundle(String tenantId, Map<String, Object> modelConfig, Long sessionId) {
        // TODO 接入 Spring AI ChatClient/EmbeddingModel 后返回真实 bundle
        return new Object();
    }

    /**
     * 解析 reference metadata 的 include/fields 偏好（1:1 还原 Python
     * resolve_reference_metadata_preferences）。
     *
     * <p>request 值优先于 config 值；兼容 legacy 请求键 include_metadata / metadata_fields。</p>
     *
     * <ul>
     *   <li>config/request 均以 {@code reference_metadata} 子 Map 承载 include/fields，request 覆盖 config；</li>
     *   <li>顶层 legacy 键 {@code include_metadata}（转 boolean）、{@code metadata_fields} 进一步覆盖；</li>
     *   <li>fields 为 null -> 返回 (include, null)；</li>
     *   <li>fields 非 List -> 打警告并返回 (include, 空集合)，后续 enrich 将跳过；</li>
     *   <li>fields 为 List -> 仅保留其中的 String 元素组成集合返回。</li>
     * </ul>
     *
     * @param requestPayload 对应 Python request_payload（本次请求入参 kwargs）
     * @param configPayload  对应 Python config_payload（对话配置 prompt_config）
     * @return include 标志与 fields 集合的组合结果
     */
    @SuppressWarnings("unchecked")
    private ReferenceMetadataPreference resolveReferenceMetadataPreferences(Map<String, Object> requestPayload,
                                                                            Map<String, Object> configPayload) {
        // 对应 Python: request_payload = request_payload or {}; config_payload = config_payload or {}
        Map<String, Object> request = requestPayload == null ? new HashMap<>() : requestPayload;
        Map<String, Object> config = configPayload == null ? new HashMap<>() : configPayload;

        // 对应 Python: config_ref = config_payload.get("reference_metadata", {})
        //             request_ref = request_payload.get("reference_metadata", {})
        Object configRef = config.get("reference_metadata");
        Object requestRef = request.get("reference_metadata");

        // 对应 Python: resolved = {}; if isinstance(config_ref, dict): resolved.update(config_ref)
        //             if isinstance(request_ref, dict): resolved.update(request_ref)
        Map<String, Object> resolved = new HashMap<>();
        if (configRef instanceof Map) {
            resolved.putAll((Map<String, Object>) configRef);
        }
        if (requestRef instanceof Map) {
            resolved.putAll((Map<String, Object>) requestRef);
        }

        // 对应 Python: if "include_metadata" in request_payload: resolved["include"] = bool(...)
        if (request.containsKey("include_metadata")) {
            resolved.put("include", getBool(request.get("include_metadata"), false));
        }
        // 对应 Python: if "metadata_fields" in request_payload: resolved["fields"] = ...
        if (request.containsKey("metadata_fields")) {
            resolved.put("fields", request.get("metadata_fields"));
        }

        // 对应 Python: include_metadata = bool(resolved.get("include", False))
        boolean includeMetadata = getBool(resolved.get("include"), false);
        Object fields = resolved.get("fields");

        // 对应 Python: if fields is None: return include_metadata, None
        if (fields == null) {
            return new ReferenceMetadataPreference(includeMetadata, null);
        }
        // 对应 Python: if not isinstance(fields, list): logger.warning(...); return include_metadata, set()
        if (!(fields instanceof List)) {
            log.warn("reference_metadata.fields is not a list; include_metadata={} fields={} type={} resolved={}."
                            + " enrich_chunks_with_document_metadata will skip enrichment.",
                    includeMetadata, fields, fields.getClass().getSimpleName(), resolved);
            return new ReferenceMetadataPreference(includeMetadata, new LinkedHashSet<>());
        }
        // 对应 Python: return include_metadata, {f for f in fields if isinstance(f, str)}
        Set<String> fieldSet = new LinkedHashSet<>();
        for (Object f : (List<Object>) fields) {
            if (f instanceof String s) {
                fieldSet.add(s);
            }
        }
        return new ReferenceMetadataPreference(includeMetadata, fieldSet);
    }

    /**
     * TODO 对应 use_sql：基于自然语言生成并执行 SQL。当前直接返回 null 触发向量检索回退。
     */
    private Map<String, Object> useSql(String question, Map<String, String> fieldMap, String tenantId,
                                       Object chatMdl, boolean quota, List<Long> kbIds) {
        // TODO 接入文档引擎 SQL 检索
        return null;
    }

    /**
     * TODO 对应 _enrich_chunks_with_document_metadata：为 chunk 补充文档元数据。
     */
    private void enrichChunksWithDocumentMetadata(List<Map<String, Object>> chunks, Set<String> metadataFields) {
        // TODO 接入文档元数据服务
    }

    /**
     * 多轮对话问题改写（百分百还原 RagFlow rag.prompts.generator.full_question）。
     *
     * <p>对应 Python：async def full_question(tenant_id=None, llm_id=None, messages=[], language=None, chat_mdl=None)。
     * 调用点未传 language，等价 Python language=None（模板走"与原问题同语言"分支）。RagFlow 中当 chat_mdl 为空时会依据
     * tenant_id/llm_id 解析模型（image2text 优先），当前项目统一使用 Spring AI 自动装配的 {@link #chatModel}，
     * 因此 tenantId/llmId 仅保留以对齐签名。</p>
     *
     * <p>流程：拼接 user/assistant 会话 -> 计算 today/yesterday/tomorrow -> 渲染 FULL_QUESTION_PROMPT_TEMPLATE
     * -> LLM 生成 -> 去除 &lt;/think&gt; 及其之前内容 -> 命中 **ERROR** 则回退到 messages[-1].content。</p>
     */
    private String fullQuestion(List<ChatMessageBO> messages) {
        // 对应 Python：conv = []；for m in messages: if role in ["user","assistant"]: conv.append("ROLE: content")
        List<String> conv = new ArrayList<>();
        // 对话历史扁平化处理
        /*
          messages = [
              {"role": "user", "content": "帮我查一下周杰伦哪年出生的？"},
              {"role": "assistant", "content": "周杰伦出生于 1979 年 1 月 18 日。"},
              {"role": "user", "content": "他明天要在哪里开演唱会吗？"} # 用户最后一句提问
          ]
          变成
          USER: 帮我查一下周杰伦哪年出生的？
          ASSISTANT: 周杰伦出生于 1979 年 1 月 18 日。
          USER: 他明天要在哪里开演唱会吗？
         */
        for (ChatMessageBO m : messages) {
            String role = m.getRole();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            conv.add(role.toUpperCase() + ": " + (m.getContent() == null ? "" : m.getContent()));
        }
        String conversation = String.join("\n", conv);

        // 对应 Python：today / yesterday / tomorrow 的 ISO 日期
        LocalDate todayDate = java.time.LocalDate.now();
        String today = todayDate.toString();
        String yesterday = todayDate.minusDays(1).toString();
        String tomorrow = todayDate.plusDays(1).toString();

        // 对应 Python：language=None -> 走"与原问题同语言"分支
        String language = null;

        // 对应 Python：template.render(today, yesterday, tomorrow, conversation, language)
        String renderedPrompt = renderFullQuestionPrompt(today, yesterday, tomorrow, conversation, language);

        // 对应 Python：ans = await chat_mdl.async_chat(rendered_prompt, [{"role":"user","content":"Output: "}])
        List<Map<String, Object>> userMsg = new ArrayList<>();
        Map<String, Object> output = new HashMap<>();
        output.put("role", "user");
        output.put("content", "Output: ");
        userMsg.add(output);
        String ans = asyncChatCall(chatModel, renderedPrompt, userMsg);

        // 对应 Python：ans = re.sub(r"^.*</think>", "", ans, flags=re.DOTALL)
        // 清除思考标签（针对推理模型）：r"^.*</think>"：匹配从字符串开头（^）一直到第一个 </think> 标签结束的所有内容。
        // 许多现代推理模型（如 DeepSeek-R1、OpenAI o1/o3-mini 等）在输出最终答案前，会先输出一段包含在 <think>...</think> 内的思维链（CoT）。
        // 作用：把模型返回的文本中，从开头到 </think> 的所有思考细节抹去，只保留 </think> 之后的纯净最终答案。
        ans = ans == null ? "" : ans.replaceFirst("(?s)^.*</think>", "");

        // 对应 Python：return ans if ans.find("**ERROR**") < 0 else messages[-1]["content"]
        // 错误检测与兜底返回：在 ans 字符串中寻找是否包含 ERROR 这个关键词。
        // 如果没有错误：返回处理干净后的 ans（正常输出）。
        // 如果包含 ERROR：说明模型在生成过程中遇到了问题，或者触发了提示词中预设的错误拦截（例如：输入不合规、模型无法解析等）。此时代码会触发兜底机制（Fallback），直接返回 messages[-1]["content"]，也就是用户最后一次输入的原始文本，确保程序不会因为模型的垃圾输出而崩掉。
        return !ans.contains("**ERROR**") ? ans : messages.getLast().getContent();
    }

    /**
     * 渲染 RagFlow full_question_prompt 模板（提示词与代码解耦 + Spring AI 2.0 PromptTemplate 渲染）。
     *
     * <p>对应 Python：PROMPT_JINJA_ENV.from_string(FULL_QUESTION_PROMPT_TEMPLATE).render(...)。
     * RagFlow 用 Jinja2，本项目用 Spring AI 2.0 的 {@link PromptTemplate}（占位符 {var}，仅变量替换、
     * 不支持 if 逻辑）。Python 中的 {% if language %} 分支：由 Java 决定选用哪句短文案（仅一行文本，直接内联），
     * 组装出的整句 languageHint 作为变量注入主模板。</p>
     */
    private String renderFullQuestionPrompt(String today, String yesterday, String tomorrow,
                                            String conversation, String language) {
        // 对应 Jinja {% if language %}...{% else %}...{% endif %}：选文案 + 填充 language，得到一句完整提示
        String languageHint = StringUtils.isNotBlank(language)
                ? "- Text generated MUST be in " + language + "."
                : "- Text generated MUST be in the same language as the original user's question.";

        // 用 Spring AI 2.0 PromptTemplate 渲染主模板的 {var} 占位符
        Map<String, Object> variables = new HashMap<>();
        variables.put("today", today);
        variables.put("yesterday", yesterday);
        variables.put("tomorrow", tomorrow);
        variables.put("conversation", conversation);
        variables.put("language_hint", languageHint);
        return new PromptTemplate(loadPrompt("full_question_prompt")).render(variables);
    }

    /**
     * 从 classpath 加载提示词模板并缓存（对应 RagFlow rag.prompts.template.load_prompt）。
     * 这段代码的核心功能是：从项目的类路径（Classpath）中动态加载 Markdown 格式的提示词（Prompt）文件，并将其缓存到内存中，以避免重复读取文件带来的 I/O 开销。
     *
     * <p>模板位于 classpath:prompts/{name}.md，读取后 strip 首尾空白并缓存，行为与 Python 版一致。</p>
     */
    private String loadPrompt(String name) {
        // computeIfAbsent ： 如果不存在（Absent），则计算（Compute）
        return PROMPT_CACHE.computeIfAbsent(name, key -> {
            String resource = "prompts/" + key + ".md";
            // getClassLoader().getResourceAsStream(resource)：从 Java 的类路径（如编译后的 target/classes 或 src/main/resources 目录）中寻找并读取文件。
            // 这在 Web 开发或打成 JAR 包运行的场景中非常标准。
            // try-with-resources：使用 try (...) 的语法结构，确保无论读取是否成功，InputStream 流最终都会被自动关闭，防止内存泄漏。
            try (InputStream in = AsyncChatServiceImpl.class.getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new IllegalStateException("Prompt file '" + key + ".md' not found in classpath prompts/ directory.");
                }
                String content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return content.strip();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to load prompt '" + key + "': " + e.getMessage(), e);
            }
        });
    }

    /**
     * TODO 对应 cross_languages：跨语言问题改写。
     */
    private String crossLanguages(String tenantId, String llmId, String question, Object langs) {
        // TODO 接入跨语言改写
        return question;
    }

    /**
     * TODO 对应 apply_meta_data_filter：按元数据过滤 doc 范围。
     */
    private List<Long> applyMetaDataFilter(Map<String, Object> metaDataFilter, String question,
                                           Object chatMdl, List<Long> attachments, List<Long> kbIds) {
        // TODO 接入元数据过滤
        return attachments;
    }

    /**
     * 关键词抽取（百分百还原 RagFlow rag.prompts.generator.keyword_extraction）。
     *
     * <p>对应 Python：async def keyword_extraction(chat_mdl, content, topn=3)。调用点
     * {@code keywordExtraction(chatModel, last)} 未传 topn，等价 Python 默认 topn=3。RagFlow 中
     * chat_mdl 为 LLMBundle，本项目统一使用 Spring AI 自动装配的 {@link #chatModel}，因此签名保留
     * chatMdl 仅用于对齐（实际忽略，与 {@link #fullQuestion} 处理一致）。</p>
     *
     * <p>流程：渲染 KEYWORD_PROMPT_TEMPLATE(content, topn) -> 构造 [system, user "Output: "] 消息 ->
     * message_fit_in 裁剪 -> LLM 生成(temperature=0.2) -> 去除 &lt;/think&gt; 及其之前内容 ->
     * 命中 **ERROR** 返回空串 -> 返回关键词字符串。</p>
     *
     * @param chatMdl  对齐 Python chat_mdl（实际使用 {@link #chatModel}）
     * @param question 对应 Python content：待抽取关键词的文本
     * @return 以英文逗号分隔的关键词字符串；命中错误时返回空串
     */
    private String keywordExtraction(Object chatMdl, String question) {
        // 对应 Python：topn=3（调用点未传，使用默认值）
        int topn = 3;

        // 对应 Python：template = PROMPT_JINJA_ENV.from_string(KEYWORD_PROMPT_TEMPLATE)
        //             rendered_prompt = template.render(content=content, topn=topn)
        String renderedPrompt = renderKeywordPrompt(question, topn);

        // 对应 Python：msg = [{"role":"system","content":rendered_prompt}, {"role":"user","content":"Output: "}]
        List<Map<String, Object>> msg = new ArrayList<>();
        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", renderedPrompt);
        msg.add(systemMsg);
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "Output: ");
        msg.add(userMsg);

        // 对应 Python：_, msg = message_fit_in(msg, chat_mdl.max_length)
        // RagFlow 用 chat_mdl.max_length 作为上限，本项目沿用桩实现 messageFitIn（当前不做实际裁剪）。
        FitInResult fitIn = messageFitIn(msg, numTokensFromString(renderedPrompt) + 1);
        msg = fitIn.msg;

        // 对应 Python：kwd = await chat_mdl.async_chat(rendered_prompt, msg[1:], {"temperature": 0.2})
        // 传入 system=rendered_prompt 与除 system 外的消息（msg[1:]），temperature=0.2 在 RagFlow 中作为
        // 生成配置；当前 asyncChatCall 三参版本沿用 Spring AI 默认配置（与 fullQuestion 处理保持一致）。
        List<Map<String, Object>> msgWithoutSystem = new ArrayList<>(msg.subList(1, msg.size()));
        String kwd = asyncChatCall(chatModel, renderedPrompt, msgWithoutSystem);

        // 对应 Python：if isinstance(kwd, tuple): kwd = kwd[0]
        // Java 下 asyncChatCall 恒返回 String，等价已取标量结果，无需额外处理。
        kwd = StringUtils.isBlank(kwd) ? "" : kwd;

        // 对应 Python：kwd = re.sub(r"^.*</think>", "", kwd, flags=re.DOTALL)
        // 去除推理模型思维链：从开头到最后（DOTALL 贪婪）一个 </think> 的所有内容。
        kwd = kwd.replaceFirst("(?s)^.*</think>", "");

        // 对应 Python：if kwd.find("**ERROR**") >= 0: return ""
        if (kwd.contains("**ERROR**")) {
            return "";
        }

        // 对应 Python：return kwd
        return kwd;
    }

    /**
     * 渲染 RagFlow keyword_prompt 模板（提示词与代码解耦 + Spring AI 2.0 PromptTemplate 渲染）。
     *
     * <p>对应 Python：PROMPT_JINJA_ENV.from_string(KEYWORD_PROMPT_TEMPLATE).render(content=content, topn=topn)。
     * RagFlow 用 Jinja2（{{ content }} / {{ topn }}），本项目 classpath 下的 keyword_prompt.md 使用
     * Spring AI {@link PromptTemplate} 占位符（{content} / {topn}），仅做变量替换，语义完全一致。</p>
     */
    private String renderKeywordPrompt(String content, int topn) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("content", StringUtils.isBlank(content) ? "" : content);
        variables.put("topn", topn);
        return new PromptTemplate(loadPrompt("keyword_prompt")).render(variables);
    }

    /**
     * TODO 对应 DeepResearcher.research：深度检索并逐条 yield 检索过程标记。
     */
    private void deepResearch(Object chatMdl, Map<String, Object> promptConfig, Object embdMdl,
                              List<String> tenantIds, DialogBO dialog, List<Long> attachments,
                              Map<String, Object> kbinfos, List<String> questions,
                              Consumer<ChatAnswerBO> consumer) {
        // 对应 Python: yield <retrieving> ... </retrieving> 包裹深度检索过程
        ChatAnswerBO start = new ChatAnswerBO();
        start.setAnswer("<retrieving>");
        start.setReference(new HashMap<>());
        start.setFinalFlag(Boolean.FALSE);
        consumer.accept(start);
        // TODO 接入 DeepResearcher，中间过程逐条 yield，并回填 kbinfos
        ChatAnswerBO end = new ChatAnswerBO();
        end.setAnswer("</retrieving>");
        end.setReference(new HashMap<>());
        end.setFinalFlag(Boolean.FALSE);
        consumer.accept(end);
    }


    /**
     * TODO 对应 retriever.retrieval_by_toc：基于目录结构增强检索。
     */
    private List<Map<String, Object>> retrievalByToc(Object retriever, String question,
                                                     List<Map<String, Object>> chunks, List<String> tenantIds,
                                                     Object chatMdl, int topN) {
        // TODO 接入 TOC 增强检索
        return null;
    }

    /**
     * TODO 对应 retriever.retrieval_by_children：父子分块检索。
     */
    private List<Map<String, Object>> retrievalByChildren(Object retriever,
                                                          List<Map<String, Object>> chunks, List<String> tenantIds) {
        // TODO 接入父子分块检索
        return chunks;
    }

    /**
     * TODO 对应 Tavily.retrieve_chunks：网络搜索检索。
     */
    private Map<String, Object> tavilyRetrieve(String apiKey, String question) {
        // TODO 接入 Tavily 网搜
        return newKbinfos();
    }

    /**
     * TODO 对应 settings.kg_retriever.retrieval：知识图谱检索。
     */
    private Map<String, Object> kgRetrieval(String question, List<String> tenantIds, List<Long> kbIds,
                                            Object embdMdl, String tenantId,
                                            Long sessionId) {
        // TODO 接入知识图谱检索
        return null;
    }

    /**
     * TODO 对应 label_question：为问题打标签用于 rank 特征。
     * 根据用户输入的问答问题（question）以及一组知识库对象（kbs），自动识别并打上相关的标签（Tags），以便后续进行更精准的知识检索。
     */
    private Object labelQuestion(String question, List<KnowledgeBasePO> kbs) {
        // TODO 接入 rank 特征标注
        return null;
    }

    /**
     * TODO 对应 kb_prompt：将检索结果格式化为知识文本列表。
     */
    private List<String> kbPrompt(Map<String, Object> kbinfos, int maxTokens) {
        // TODO 接入知识文本格式化
        return new ArrayList<>();
    }

    /**
     * TODO 对应 citation_prompt：生成引用规范提示词。
     */
    private String citationPrompt() {
        // TODO 接入引用提示词模板
        return "";
    }

    /**
     * TODO 对应 message_fit_in：按 token 上限裁剪消息，返回已用 token 数与裁剪后消息。
     */
    private FitInResult messageFitIn(List<Map<String, Object>> msg, int maxTokens) {
        // TODO 接入 token 裁剪逻辑，当前直接返回原消息
        int used = 0;
        for (Map<String, Object> m : msg) {
            used += numTokensFromString(strOf(m.get("content")));
        }
        return new FitInResult(used, msg);
    }

    /**
     * TODO 对应 langfuse_generation.update/end：结束一次生成观测。
     */
    private void endLangfuseObservation(Object generation, String prompt, int inputTokens, int outputTokens) {
        // TODO 接入 langfuse
    }

    /**
     * TODO 对应 chat_mdl.async_chat_streamly_delta + _stream_with_think_delta（有知识库分支）。
     * 逐块增量回调 answer，并处理 &lt;think&gt;/&lt;/think&gt; 标记，返回最终流状态。
     */
    private ThinkStreamState streamlyDelta(Object chatMdl, String system, List<Map<String, Object>> msg,
                                           Map<String, Object> genConf, List<Object> images,
                                           Consumer<ChatAnswerBO> consumer, Object ttsMdl) {
        // TODO 接入真实 LLM 流式生成 + think 拆分。当前返回空状态。
        ThinkStreamState state = new ThinkStreamState();
        state.fullText = "";
        return state;
    }


    /**
     * 基于 Spring AI 2.0 {@link ChatModel} 的流式生成（async_chat_solo 流式分支）。
     * 逐块增量回调 answer，并在流结束后回调一个 final=true 结果。
     */
    private void streamSoloDelta(ChatModel chatMdl, String system, List<Map<String, Object>> msg,
                                 Consumer<ChatAnswerBO> consumer) {
        Prompt prompt = buildPrompt(system, msg);
        StringBuilder full = new StringBuilder();
        chatMdl.stream(prompt).toStream().forEach(resp -> {
            String delta = extractText(resp);
            if (delta == null || delta.isEmpty()) {
                return;
            }
            full.append(delta);
            ChatAnswerBO chunk = new ChatAnswerBO();
            chunk.setAnswer(delta);
            chunk.setReference(new HashMap<>());
            chunk.setFinalFlag(Boolean.FALSE);
            consumer.accept(chunk);
        });
        ChatAnswerBO fin = new ChatAnswerBO();
        fin.setAnswer("");
        fin.setReference(new HashMap<>());
        fin.setFinalFlag(Boolean.TRUE);
        putExtra(fin, "created_at", System.currentTimeMillis() / 1000.0);
        consumer.accept(fin);
    }

    /**
     * 基于 Spring AI 2.0 {@link ChatModel} 的非流式一次性生成完整答案。
     */
    private String asyncChatCall(ChatModel chatMdl, String system, List<Map<String, Object>> msg) {
        Prompt prompt = buildPrompt(system, msg);
        ChatResponse response = chatMdl.call(prompt);
        String text = extractText(response);
        return StringUtils.isBlank(text) ? "" : text;
    }

    /**
     * 将 system 文本与 role/content 消息列表转换为 Spring AI {@link Prompt}。
     */
    private Prompt buildPrompt(String system, List<Map<String, Object>> msg) {
        List<Message> messages = new ArrayList<>();
        if (StringUtils.isNotBlank(system)) {
            messages.add(new SystemMessage(system));
        }
        for (Map<String, Object> m : msg) {
            String role = strOf(m.get("role"));
            String content = strOf(m.get("content"));
            // todo 为啥这里没有tool类型
            if ("assistant".equals(role)) {
                messages.add(new AssistantMessage(content));
            } else {
                messages.add(new UserMessage(content));
            }
        }
        return new Prompt(messages);
    }

    /**
     * 从 Spring AI {@link ChatResponse} 中提取文本内容（兼容流式增量与完整响应）。
     */
    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return StringUtils.isBlank(text) ? "" : text;
    }

    /**
     * TODO 对应 _hydrate_chunk_vectors：为待引用 chunk 拉取向量。
     */
    private void hydrateChunkVectors(Object retriever, List<Map<String, Object>> chunks,
                                     List<String> tenantIds, List<Long> kbIds) {
        // TODO 接入 chunk 向量补齐
    }

    /**
     * TODO 对应 retriever.insert_citations：在答案中插入引用标记并返回命中 chunk 索引。
     */
    private InsertCitationsResult insertCitations(Object retriever, String answer, List<String> contentLtks,
                                                  List<Object> vectors, Object embdMdl,
                                                  double tkweight, double vtweight) {
        // TODO 接入引用注入
        return new InsertCitationsResult(answer, new LinkedHashSet<>());
    }

    /**
     * TODO 对应 num_tokens_from_string：估算 token 数。
     */
    private int numTokensFromString(String text) {
        // TODO 接入分词器，当前用字符数近似
        return text == null ? 0 : text.length();
    }

    /**
     * TODO 对应 normalize_arabic_digits：将阿拉伯数字归一化为 ASCII 数字。
     */
    private String normalizeArabicDigits(String text) {
        // TODO 接入阿拉伯数字归一化，当前直接返回
        return text == null ? "" : text;
    }


    // ==================== 集合/类型工具 ====================
    private String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private boolean getBool(Object o, boolean defaultValue) {
        if (o == null) {
            return defaultValue;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        if (o instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    private <T> List<T> lastN(List<T> list, int n) {
        if (list.size() <= n) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>(list.subList(list.size() - n, list.size()));
    }

    private Map<String, Object> newKbinfos() {
        Map<String, Object> kbinfos = new HashMap<>();
        kbinfos.put("total", 0);
        kbinfos.put("chunks", new ArrayList<Map<String, Object>>());
        kbinfos.put("doc_aggs", new ArrayList<Map<String, Object>>());
        return kbinfos;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunksOf(Map<String, Object> kbinfos) {
        Object v = kbinfos.get("chunks");
        if (!(v instanceof List)) {
            List<Map<String, Object>> list = new ArrayList<>();
            kbinfos.put("chunks", list);
            return list;
        }
        return (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> docAggsOf(Map<String, Object> kbinfos) {
        Object v = kbinfos.get("doc_aggs");
        if (!(v instanceof List)) {
            List<Map<String, Object>> list = new ArrayList<>();
            kbinfos.put("doc_aggs", list);
            return list;
        }
        return (List<Map<String, Object>>) v;
    }

    private List<String> contentLtksOf(Map<String, Object> kbinfos) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> c : chunksOf(kbinfos)) {
            result.add(strOf(c.get("content_ltks")));
        }
        return result;
    }

    private List<Object> vectorsOf(Map<String, Object> kbinfos) {
        List<Object> result = new ArrayList<>();
        for (Map<String, Object> c : chunksOf(kbinfos)) {
            result.add(c.get("vector"));
        }
        return result;
    }

    private Map<String, Object> deepCopyKbinfos(Map<String, Object> kbinfos) {
        Map<String, Object> copy = new HashMap<>();
        copy.put("total", kbinfos.get("total"));
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (Map<String, Object> c : chunksOf(kbinfos)) {
            chunks.add(new HashMap<>(c));
        }
        List<Map<String, Object>> docAggs = new ArrayList<>();
        for (Map<String, Object> d : docAggsOf(kbinfos)) {
            docAggs.add(new HashMap<>(d));
        }
        copy.put("chunks", chunks);
        copy.put("doc_aggs", docAggs);
        return copy;
    }

    private boolean hasChunks(Map<String, Object> ans) {
        Object reference = ans.get("reference");
        if (reference instanceof Map<?, ?> refMap) {
            Object chunks = refMap.get("chunks");
            return chunks instanceof List<?> list && !list.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunksOfAns(Map<String, Object> ans) {
        Object reference = ans.get("reference");
        if (reference instanceof Map<?, ?> refMap) {
            Object chunks = refMap.get("chunks");
            if (chunks instanceof List) {
                return (List<Map<String, Object>>) chunks;
            }
        }
        return new ArrayList<>();
    }

    /**
     * 将 use_sql 返回的 dict 转为 ChatAnswerBO。
     */
    @SuppressWarnings("unchecked")
    private ChatAnswerBO mapToAnswer(Map<String, Object> ans) {
        ChatAnswerBO bo = new ChatAnswerBO();
        bo.setAnswer(strOf(ans.get("answer")));
        Object reference = ans.get("reference");
        bo.setReference(reference instanceof Map ? (Map<String, Object>) reference : new HashMap<>());
        if (ans.get("prompt") != null) {
            putExtra(bo, "prompt", ans.get("prompt"));
        }
        return bo;
    }

    private List<String> collectParamKeys(Map<String, Object> promptConfig) {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> p : parametersOf(promptConfig)) {
            keys.add(strOf(p.get("key")));
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parametersOf(Map<String, Object> promptConfig) {
        Object v = promptConfig.get("parameters");
        if (v instanceof List) {
            return (List<Map<String, Object>>) v;
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private void addKnowledgeParam(Map<String, Object> promptConfig) {
        Object v = promptConfig.get("parameters");
        List<Map<String, Object>> params;
        if (v instanceof List) {
            params = (List<Map<String, Object>>) v;
        } else {
            params = new ArrayList<>();
            promptConfig.put("parameters", params);
        }
        Map<String, Object> knowledge = new HashMap<>();
        knowledge.put("key", "knowledge");
        knowledge.put("optional", false);
        params.add(knowledge);
    }

    private List<String> userIdsOf(List<KnowledgeBasePO> kbs) {
        // 对应 list(set([kb.tenant_id for kb in kbs]))
        Set<String> set = new LinkedHashSet<>();
        if (!CollectionUtils.isEmpty(kbs)) {
            for (KnowledgeBasePO kb : kbs) {
                if (kb != null && StringUtils.isNotBlank(kb.getUserId())) {
                    set.add(kb.getUserId());
                }
            }
        }
        return new ArrayList<>(set);
    }

    private void putExtra(ChatAnswerBO ans, String key, Object value) {
        if (ans.getExtra() == null) {
            ans.setExtra(new HashMap<>());
        }
        ans.getExtra().put(key, value);
    }

    // ==================== 内部数据结构 ====================

    /**
     * 对应 get_models 返回的模型集合。
     */
    private static class ModelBundle {
        List<Object> kbs;
        Object embdMdl;
        Object rerankMdl;
        Object chatMdl;
        Object ttsMdl;
    }

    /**
     * decorate_answer 所需的上下文（对应 Python 闭包捕获的 nonlocal 变量）。
     */
    private static class DecorateContext {
        Object embdMdl;
        Map<String, Object> promptConfig;
        List<String> knowledges;
        Map<String, Object> kwargs;
        Map<String, Object> kbinfos;
        Object retriever;
        List<String> userIds;
        DialogBO dialog;
        List<String> questions;
        String prompt;
        long chatStartTs;
        long checkLlmTs;
        long checkLangfuseTracerTs;
        long bindModelsTs;
        long refineQuestionTs;
        long retrievalTs;
        int usedTokenCount;
        Object langfuseGeneration;
    }

    /**
     * split_file_attachments 结果。
     */
    private static class SplitFilesResult {
        final List<String> textAttachments;
        final List<String> imageAttachments;
        final List<Object> imageFiles;

        SplitFilesResult(List<String> textAttachments, List<String> imageAttachments, List<Object> imageFiles) {
            this.textAttachments = textAttachments;
            this.imageAttachments = imageAttachments;
            this.imageFiles = imageFiles;
        }
    }

    /**
     * message_fit_in 结果。
     */
    private static class FitInResult {
        final int usedTokenCount;
        final List<Map<String, Object>> msg;

        FitInResult(int usedTokenCount, List<Map<String, Object>> msg) {
            this.usedTokenCount = usedTokenCount;
            this.msg = msg;
        }
    }

    /**
     * insert_citations 结果。
     */
    private static class InsertCitationsResult {
        final String answer;
        final Set<Integer> idx;

        InsertCitationsResult(String answer, Set<Integer> idx) {
            this.answer = answer;
            this.idx = idx;
        }
    }

    /**
     * repair_bad_citation_formats 结果。
     */
    private static class RepairResult {
        final String answer;
        final Set<Integer> idx;

        RepairResult(String answer, Set<Integer> idx) {
            this.answer = answer;
            this.idx = idx;
        }
    }

    /**
     * 流式 think 状态（对应 Python _ThinkStreamState，此处仅保留还原所需字段）。
     */
    private static class ThinkStreamState {
        String fullText = "";
    }

    /**
     * resolve_reference_metadata_preferences 结果（对应 Python 返回的 tuple[bool, set[str] | None]）。
     *
     * <p>{@code include} 对应元组第一项 include_metadata；{@code fields} 对应第二项：
     * null 表示未指定字段（Python None），空集合表示 fields 非法被降级，非空集合为过滤后的字符串字段。</p>
     */
    private static class ReferenceMetadataPreference {
        final boolean include;
        final Set<String> fields;

        ReferenceMetadataPreference(boolean include, Set<String> fields) {
            this.include = include;
            this.fields = fields;
        }
    }

}