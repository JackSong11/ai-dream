package com.example.dream.service.core.chat.impl;

import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.service.biz.bo.chat.DialogBO;
import com.example.dream.service.core.chat.AsyncChatService;
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
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            return;
        }

        long chatStartTs = System.nanoTime();

        // 模型配置解析（对应 dia.llm_id -> get_model_config_from_provider_instance / 默认模型）

        long checkLlmTs = System.nanoTime();

        // get_models：kbs / embd_mdl / rerank_mdl / chat_mdl / tts_mdl
        ModelBundle models = getModels(dialog, traceContext, convId);

        // todo 这里需要换成ES
        // 🔋 模块一：上下文解析与附件提取
        Object retriever = getRetriever();
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
        List<String> attachments = null;

        String attachmentsText = "";
        List<String> imageAttachments = new ArrayList<>();
        List<Object> imageFiles = new ArrayList<>();

        Map<String, Object> promptConfig = dialog.getPromptConfig() == null
                ? new HashMap<>() : new HashMap<>(dialog.getPromptConfig());

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
        log.debug("attachments={}, param_keys={}, embd_mdl={}", attachments, paramKeys, models.embdMdl);

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
                    fullQuestion(dialog.getUserId(), dialog.getLlmId(), messages)));
        } else {
            // 如果不满足条件（例如：关闭了精炼功能，或者只有一轮对话）。
            // 作用：切片操作 [-1:] 表示只保留最后一条问题（即用户最新输入的那句话），直接丢弃之前的历史问题。
            questions = lastN(questions, 1);
        }

        // 跨语言翻译处理
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
                    questions.get(questions.size() - 1), models.chatMdl, attachments, dialog.getKbIds());
        }

        if (getBool(promptConfig.get("keyword"), false)) {
            String last = questions.get(questions.size() - 1);
            questions.set(questions.size() - 1,
                    last + "," + keywordExtraction(models.chatMdl, last));
        }
        long refineQuestionTs = System.nanoTime();

        // ====== 检索阶段 ======
        String thought = "";
        Map<String, Object> kbinfos = newKbinfos();
        List<String> knowledges;

        if (paramKeys.contains("knowledge")) {
            log.debug("Proceeding with retrieval");
            List<String> tenantIds = tenantIdsOf(models.kbs);
            if (getBool(promptConfig.get("reasoning"), false) || getBool(kwargs.get("reasoning"), false)) {
                // DeepResearcher 深度检索（桩占位，仍逐条 yield 检索过程标记）
                deepResearch(models.chatMdl, promptConfig, models.embdMdl, tenantIds, dialog,
                        attachments, useWebSearch, kbinfos, questions, chunkConsumer);
            } else {
                if (models.embdMdl != null) {
                    kbinfos = retrieval(retriever, String.join(" ", questions), models.embdMdl,
                            tenantIds, dialog.getKbIds(), 1, dialog.getTopN(),
                            dialog.getSimilarityThreshold(), dialog.getVectorSimilarityWeight(),
                            attachments, dialog.getTopK(), true, models.rerankMdl,
                            labelQuestion(String.join(" ", questions), models.kbs));
                    if (getBool(promptConfig.get("toc_enhance"), false)) {
                        List<Map<String, Object>> cks = retrievalByToc(retriever,
                                String.join(" ", questions), chunksOf(kbinfos), tenantIds,
                                models.chatMdl, dialog.getTopN());
                        if (cks != null && !cks.isEmpty()) {
                            kbinfos.put("chunks", cks);
                        }
                    }
                    kbinfos.put("chunks", retrievalByChildren(retriever, chunksOf(kbinfos), tenantIds));
                }
                if (useWebSearch) {
                    Map<String, Object> tavRes = tavilyRetrieve(strOf(promptConfig.get("tavily_api_key")),
                            String.join(" ", questions));
                    chunksOf(kbinfos).addAll(chunksOf(tavRes));
                    docAggsOf(kbinfos).addAll(docAggsOf(tavRes));
                }
                if (getBool(promptConfig.get("use_kg"), false)) {
                    Map<String, Object> ck = kgRetrieval(String.join(" ", questions), tenantIds,
                            dialog.getKbIds(), models.embdMdl, dialog.getUserId(), traceContext, convId);
                    if (ck != null && isNotBlank(strOf(ck.get("content_with_weight")))) {
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
            ans.setAudioBinary(tts(models.ttsMdl, emptyRes));
            ans.setFinalFlag(Boolean.TRUE);
            putExtra(ans, "prompt", "\n\n### Query:\n" + String.join(" ", questions));
            chunkConsumer.accept(ans);
            return;
        }

        // knowledge 拼接
        String knowledgeText = String.join("\n\n------\n\n", knowledges);
        if (isNotBlank(knowledgeText)) {
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
        String modelType = llmModelConfig == null ? "chat" : String.valueOf(llmModelConfig.get("model_type"));
        if ("chat".equals(modelType) && !imageAttachments.isEmpty()) {
            convertLastUserMsgToMultimodal(msg, imageAttachments, factory);
        }
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
        dc.embdMdl = models.embdMdl;
        dc.promptConfig = promptConfig;
        dc.knowledges = knowledges;
        dc.kwargs = kwargs;
        dc.kbinfos = kbinfos;
        dc.retriever = retriever;
        dc.tenantIds = tenantIdsOf(models.kbs);
        dc.dialog = dialog;
        dc.questions = questions;
        dc.prompt = prompt;
        dc.chatStartTs = chatStartTs;
        dc.checkLlmTs = checkLlmTs;
        dc.checkLangfuseTracerTs = checkLangfuseTracerTs;
        dc.bindModelsTs = bindModelsTs;
        dc.refineQuestionTs = refineQuestionTs;
        dc.retrievalTs = retrievalTs;
        dc.usedTokenCount = usedTokenCount;
        dc.langfuseGeneration = langfuseGeneration;

        // langfuse start_observation（桩占位）
        if (langfuseTracer != null) {
            startLangfuseObservation(traceContext, llmModelConfig, prompt, prompt4citation, msg, convId);
        }

        List<Map<String, Object>> msgWithoutSystem = msg.subList(1, msg.size());
        if (stream) {
            ThinkStreamState lastState;
            if ("chat".equals(modelType)) {
                lastState = streamlyDelta(models.chatMdl, prompt + prompt4citation, msgWithoutSystem, genConf, null, chunkConsumer, models.ttsMdl);
            } else {
                lastState = streamlyDelta(models.chatMdl, prompt + prompt4citation, msgWithoutSystem, genConf, imageFiles, chunkConsumer, models.ttsMdl);
            }
            String fullAnswer = lastState == null ? "" : lastState.fullText;
            if (isNotBlank(fullAnswer)) {
                ChatAnswerBO fin = decorateAnswer(extractVisibleAnswer(thought + fullAnswer), dc);
                fin.setFinalFlag(Boolean.TRUE);
                fin.setAnswer("");
                chunkConsumer.accept(fin);
            }
        } else {
            String answer;
            if ("chat".equals(modelType)) {
                answer = asyncChatCall(models.chatMdl, prompt + prompt4citation, msgWithoutSystem, genConf, null);
            } else {
                answer = asyncChatCall(models.chatMdl, prompt + prompt4citation, msgWithoutSystem, genConf, imageFiles);
            }
            Object userContent = msg.get(msg.size() - 1).getOrDefault("content", "[content not available]");
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
                hydrateChunkVectors(dc.retriever, chunksOf(kbinfos), dc.tenantIds, dc.dialog.getKbIds());
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
                || !isNotBlank(strOf(promptConfig.get("tavily_api_key")))) {
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
     * TODO 对应 get_models：装配 kbs / embd_mdl / rerank_mdl / chat_mdl / tts_mdl。
     */
    private ModelBundle getModels(DialogBO dialog, Map<String, Object> traceContext, Long sessionId) {
        // TODO 接入知识库/embedding/rerank/chat/tts 模型后返回真实实例
        ModelBundle bundle = new ModelBundle();
        bundle.kbs = new ArrayList<>();
        bundle.embdMdl = null;
        bundle.rerankMdl = null;
        bundle.chatMdl = newLlmBundle(dialog.getUserId(), null, sessionId);
        bundle.ttsMdl = null;
        return bundle;
    }

    /**
     * TODO 对应 LLMBundle 构造：创建聊天/嵌入/TTS 模型包装。
     */
    private Object newLlmBundle(String tenantId, Map<String, Object> modelConfig, Long sessionId) {
        // TODO 接入 Spring AI ChatClient/EmbeddingModel 后返回真实 bundle
        return new Object();
    }

    /**
     * TODO 对应 settings.retriever：获取向量检索器实例。
     */
    private Object getRetriever() {
        // TODO 接入 ES/向量检索器
        return null;
    }

    /**
     * TODO 对应 _resolve_reference_metadata 的 metadata_fields。
     */
    private List<String> resolveMetadataFields(Map<String, Object> promptConfig, Map<String, Object> kwargs) {
        // TODO 接入 reference metadata 偏好解析
        return new ArrayList<>();
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
    private void enrichChunksWithDocumentMetadata(List<Map<String, Object>> chunks, List<String> metadataFields) {
        // TODO 接入文档元数据服务
    }

    /**
     * TODO 对应 full_question：多轮对话问题改写。
     */
    private String fullQuestion(String tenantId, String llmId, List<ChatMessageBO> messages) {
        // TODO 接入多轮问题改写
        return messages.getLast().getContent();
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
    private List<String> applyMetaDataFilter(Map<String, Object> metaDataFilter, String question,
                                             Object chatMdl, List<String> attachments, List<Long> kbIds) {
        // TODO 接入元数据过滤
        return attachments;
    }

    /**
     * TODO 对应 keyword_extraction：从问题中抽取关键词。
     */
    private String keywordExtraction(Object chatMdl, String question) {
        // TODO 接入关键词抽取
        return "";
    }

    /**
     * TODO 对应 DeepResearcher.research：深度检索并逐条 yield 检索过程标记。
     */
    private void deepResearch(Object chatMdl, Map<String, Object> promptConfig, Object embdMdl,
                              List<String> tenantIds, DialogBO dialog, List<String> attachments,
                              boolean useWebSearch, Map<String, Object> kbinfos, List<String> questions,
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
     * TODO 对应 retriever.retrieval：向量+全文混合检索。
     */
    private Map<String, Object> retrieval(Object retriever, String question, Object embdMdl,
                                          List<String> tenantIds, List<Long> kbIds, int page, int pageSize,
                                          double similarityThreshold, double vectorSimilarityWeight,
                                          List<String> docIds, int top, boolean aggs, Object rerankMdl,
                                          Object rankFeature) {
        // TODO 接入 ES/向量检索
        return newKbinfos();
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
                                            Object embdMdl, String tenantId, Map<String, Object> traceContext,
                                            Long sessionId) {
        // TODO 接入知识图谱检索
        return null;
    }

    /**
     * TODO 对应 label_question：为问题打标签用于 rank 特征。
     */
    private Object labelQuestion(String question, List<Object> kbs) {
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
     * TODO 对应 langfuse start_observation：开启一次生成观测。
     */
    private void startLangfuseObservation(Map<String, Object> traceContext, Map<String, Object> llmModelConfig,
                                          String prompt, String prompt4citation, List<Map<String, Object>> msg,
                                          Long sessionId) {
        // TODO 接入 langfuse
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
        return text == null ? "" : text;
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
        return text == null ? "" : text;
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

    private List<String> tenantIdsOf(List<Object> kbs) {
        // 对应 list(set([kb.tenant_id for kb in kbs]))
        Set<String> set = new LinkedHashSet<>();
        // TODO kbs 元素类型接入后提取 tenant_id
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
        List<String> tenantIds;
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
}