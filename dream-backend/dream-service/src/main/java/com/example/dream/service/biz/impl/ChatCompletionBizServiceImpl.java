package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.service.biz.ChatCompletionBizService;
import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.service.biz.bo.chat.DialogBO;
import com.example.dream.service.core.ConversationCoreService;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.chat.AsyncChatService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link ChatCompletionBizService} 实现，还原 RagFlow chat_api.py 的 session_completion。
 *
 * <p>编排流程对应 Python：
 * 消息规范化 → dialog_id/conversation_id 解析 → 归属校验 → 会话装配（含历史消息处理）
 * → reference 预置 → 模型与生成配置合并 → 调用 async_chat 生成 → 结果结构化
 * → 会话持久化。RAG 与 LLM 由 {@link AsyncChatService} 桩实现提供。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatCompletionBizServiceImpl implements ChatCompletionBizService {

    private final DialogCoreService dialogCoreService;
    private final ConversationCoreService conversationCoreService;
    private final AsyncChatService asyncChatService;
    private final ObjectMapper objectMapper;

    /**
     * 系统默认聊天模型 ID，取自 spring.ai.openai.chat.model。
     * <p>本系统无租户概念，仅按 userId 维度使用，未显式指定 llm_id 时回退到该默认模型。</p>
     */
    @Value("${spring.ai.openai.chat.model:}")
    private String defaultChatModel;

    /**
     * 会话运行时上下文，聚合本轮编排所需的对象，减少方法间参数传递。
     */
    private static class CompletionContext {
        DialogBO dialog;
        ChatConversationPO conv;
        List<ChatMessageBO> modelMessages;
        List<ChatMessageBO> convMessages;
        List<Map<String, Object>> convReference;
        Long dialogId;
        Long convId;
        String messageId;
        boolean stream;
        Map<String, Object> extraParams;
    }

    @Override
    public void streamCompletion(ChatCompletionReqBO req, String userId, Consumer<String> payloadConsumer) {
        CompletionContext ctx = prepare(req, userId, true);
        try {
            streamStandard(ctx, payloadConsumer);
            persistConversation(ctx);
        } catch (Exception ex) {
            log.error("[ChatCompletion] stream error", ex);
            Map<String, Object> err = new HashMap<>();
            err.put("code", 500);
            err.put("message", ex.getMessage());
            Map<String, Object> data = new HashMap<>();
            data.put("answer", "**ERROR**: " + ex.getMessage());
            data.put("reference", new ArrayList<>());
            err.put("data", data);
            payloadConsumer.accept(toJson(err));
        }
        // 对应 Python 结尾：yield data: {"code":0,"message":"","data":true}
        Map<String, Object> done = new HashMap<>();
        done.put("code", 0);
        done.put("message", "");
        done.put("data", Boolean.TRUE);
        payloadConsumer.accept(toJson(done));
    }

    @Override
    public ChatAnswerBO completion(ChatCompletionReqBO req, String userId) {

        CompletionContext ctx = prepare(req, userId, false);
        AtomicReference<ChatAnswerBO> holder = new AtomicReference<>();
        // 对应 Python: async for ans in async_chat(...): answer = _format_answer(ans);
        //             if conv is not None: update_by_id(...); break
        // 仅取第一个结果并结构化，conv 非空时立即持久化（persistConversation 内部已判空）。
        asyncChatService.asyncChat(ctx.dialog, ctx.modelMessages, false, ctx.convId, ctx.extraParams, ans -> {
            if (holder.get() == null) {
                holder.set(formatAnswer(ctx, ans));
                persistConversation(ctx);
            }
        });
        // 对应 Python: return get_json_result(data=_sanitize_json_floats(answer))
        // 清洗返回结果中的 NaN/Infinity 浮点值，替换为 null，保证输出为合法 JSON。
        return sanitizeJsonFloats(holder.get());
    }

    /**
     * 准备阶段：还原 session_completion 中从消息规范化到模型配置合并的全部前置逻辑。
     */
    private CompletionContext prepare(ChatCompletionReqBO req, String userId, boolean stream) {
        CompletionContext ctx = new CompletionContext();
        ctx.stream = stream;

        // 1. 规范化消息，得到 (完整消息 request_messages, 精简后 msg)
        List<ChatMessageBO> requestMessages = normalizeMessages(req);
        List<ChatMessageBO> requestMsg = trimMessages(requestMessages);
        ChatMessageBO lastUser = requestMsg.getLast();
        ctx.messageId = lastUser.getId();

        boolean passAllHistory = Boolean.TRUE.equals(req.getPassAllHistoryMessages());
        ctx.dialogId = req.getDialogId();
        ctx.convId = req.getConvId();

        // 2. 透传参数与生成配置
        ctx.extraParams = req.getExtraParams() == null ? new HashMap<>() : new HashMap<>(req.getExtraParams());
        Map<String, Object> generationConfig =
                req.getGenerationConfig() == null ? new HashMap<>() : new HashMap<>(req.getGenerationConfig());

        // 3. dialog_id / conversation_id 校验与会话装配
        if (ctx.convId != null && ctx.dialogId == null) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "`dialog_id` is required when `conversation_id` is provided.");
        }

        ctx.modelMessages = requestMsg;
        if (ctx.dialogId != null) {
            ChatDialogPO dialogPo = dialogCoreService.getOwnedValidDialog(ctx.dialogId, userId);
            if (dialogPo == null) {
                throw new BizException(ResCodeEnum.UNAUTHORIZED, "No authorization.");
            }
            ctx.dialog = toDialogBO(dialogPo);

            if (ctx.convId != null) {
                ChatConversationPO conv = conversationCoreService.getById(ctx.convId);
                if (conv == null) {
                    throw new BizException(ResCodeEnum.DATA_NOT_EXIST, "Conversation not found!");
                }
                if (!Objects.equals(ctx.dialogId, conv.getDialogId())) {
                    throw new BizException(ResCodeEnum.DATA_ERROR, "Conversation does not belong to this dialog!");
                }
                ctx.conv = conv;
            } else {
                ctx.conv = createSessionForCompletion(ctx.dialogId, ctx.dialog, userId);
                ctx.convId = ctx.conv.getId();
            }

            // 历史消息处理策略（对应 pass_all_history_messages 分支）
            ctx.convMessages = parseMessages(ctx.conv.getMessage());
            if (passAllHistory) {
                // todo 这里还得再看看
                ctx.convMessages = new ArrayList<>(requestMessages);
                ctx.modelMessages = requestMsg;
            } else {
                if (ctx.convMessages == null) {
                    ctx.convMessages = new ArrayList<>();
                }
                // 在增量保存历史时，系统只把 request_msg[-1]（即当前轮的最后一条用户提问）追加到数据库的 conv.message 里。
                ctx.convMessages.add(lastUser);
                ctx.modelMessages = trimMessages(ctx.convMessages);
            }
        } else {
            ctx.dialog = DialogBO.buildDefaultCompletionDialog(userId);
        }

        // 4. reference 预置（对应 conv.reference.append({"chunks":[],"doc_aggs":[]})）
        if (ctx.conv != null) {
            ctx.convReference = parseReference(ctx.conv.getReference());
            if (ctx.convReference == null) {
                ctx.convReference = new ArrayList<>();
            }
            ctx.convReference.removeIf(java.util.Objects::isNull);
            Map<String, Object> emptyRef = new HashMap<>();
            emptyRef.put("chunks", new ArrayList<>());
            emptyRef.put("doc_aggs", new ArrayList<>());
            ctx.convReference.add(emptyRef);
        }

        // 5. todo 模型与生成配置合并（对应 chat_model_id / 默认模型分支）
        applyModelConfig(ctx, req, userId, generationConfig);
        return ctx;
    }

    /**
     * 标准流式输出（对应 session_completion 非 legacy 分支）。
     */
    private void streamStandard(CompletionContext ctx, Consumer<String> sse) {
        asyncChatService.asyncChat(ctx.dialog, ctx.modelMessages, true, ctx.convId, ctx.extraParams, ans -> {
            ChatAnswerBO formatted = formatAnswer(ctx, ans);
            Map<String, Object> payload = new HashMap<>();
            payload.put("code", 0);
            payload.put("message", "");
            payload.put("data", answerToMap(formatted));
            sse.accept(toJson(payload));
        });
    }

    /**
     * 发送单个 legacy 分块。
     */
    private void emitLegacyChunk(CompletionContext ctx, ChatAnswerBO ans, String answer, Consumer<String> sse) {
        Map<String, Object> chunk = answerToMap(ans);
        chunk.put("answer", answer);
        chunk.remove("start_to_think");
        chunk.remove("end_to_think");
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", 0);
        payload.put("message", "");
        payload.put("data", chunk);
        sse.accept(toJson(payload));
    }

    /**
     * 结构化答案（对应 structure_answer + 附加 dialog_id）。
     *
     * <p>严格还原 RagFlow structure_answer：reference 非 dict 时置空并补 chunks 字段；
     * is_final 默认为 true；每个 chunk（非仅 final）都更新会话最后一条 assistant 消息
     * （非 final 追加、final 覆盖），并按条件回写 reference[-1]。</p>
     */
    private ChatAnswerBO formatAnswer(CompletionContext ctx, ChatAnswerBO ans) {
        // 对应 Python: reference 非 dict 置空 -> reference["chunks"] = chunks_format(reference)
        Map<String, Object> reference = ans.getReference();
        if (reference == null) {
            reference = new HashMap<>();
            ans.setReference(reference);
        }
        if (!reference.containsKey("chunks")) {
            reference.put("chunks", new ArrayList<>());
        }

        ans.setId(ctx.messageId);
        ans.setConvId(ctx.convId);
        if (ctx.dialogId != null) {
            ans.setDialogId(ctx.dialogId);
        }

        // 无会话（直连补全）时不落库，直接返回（对应 Python: if not conv: return ans）
        if (ctx.conv == null) {
            return ans;
        }

        // is_final 默认为 true（对应 ans.get("final", True)）
        boolean isFinal = ans.getFinalFlag() == null || Boolean.TRUE.equals(ans.getFinalFlag());

        // content 按 think 标记调整（对应 start_to_think/end_to_think）
        String content = ans.getAnswer();
        if (Boolean.TRUE.equals(ans.getStartToThink())) {
            content = "<think>";
        } else if (Boolean.TRUE.equals(ans.getEndToThink())) {
            content = "</think>";
        }

        if (ctx.convMessages == null) {
            ctx.convMessages = new ArrayList<>();
        }
        double now = System.currentTimeMillis() / 1000.0;
        ChatMessageBO last = ctx.convMessages.isEmpty()
                ? null : ctx.convMessages.get(ctx.convMessages.size() - 1);

        if (last == null || !"assistant".equals(last.getRole())) {
            // 追加一条 assistant 消息
            ChatMessageBO assistant = ChatMessageBO.of("assistant", content);
            assistant.setCreatedAt(now);
            assistant.setId(ctx.messageId);
            ctx.convMessages.add(assistant);
        } else {
            if (isFinal) {
                if (StringUtils.isNotBlank(ans.getAnswer())) {
                    last.setRole("assistant");
                    last.setContent(ans.getAnswer());
                    last.setCreatedAt(now);
                    last.setId(ctx.messageId);
                } else {
                    last.setCreatedAt(now);
                    last.setId(ctx.messageId);
                }
            } else {
                last.setContent((last.getContent() == null ? "" : last.getContent())
                        + (content == null ? "" : content));
                last.setCreatedAt(now);
                last.setId(ctx.messageId);
            }
        }

        // reference 回写（对应 conv.reference[-1] = reference）
        if (ctx.convReference != null && !ctx.convReference.isEmpty()) {
            boolean hasChunks = !CollectionUtils.isEmpty(asCollection(reference.get("chunks")));
            boolean hasDocAggs = !CollectionUtils.isEmpty(asCollection(reference.get("doc_aggs")));
            if (isFinal || hasChunks || hasDocAggs) {
                ctx.convReference.set(ctx.convReference.size() - 1, reference);
            }
        }
        return ans;
    }

    /**
     * 将对象安全转换为集合以判空（非集合返回 null）。
     */
    @SuppressWarnings("unchecked")
    private java.util.Collection<Object> asCollection(Object obj) {
        if (obj instanceof java.util.Collection) {
            return (java.util.Collection<Object>) obj;
        }
        return null;
    }

    /**
     * 清洗答案对象中的 NaN/Infinity 浮点值（对应 RagFlow _sanitize_json_floats）。
     *
     * <p>Python 中在返回前对整个 answer dict 递归清洗，将 NaN/Infinity 替换为 None（Java 中为 null），
     * 以保证输出为符合 RFC 8259 的合法 JSON（检索相似度分数在聚合为空或分母为零时可能变为 NaN）。
     * 这里对 ChatAnswerBO 中承载动态数据的 reference 与 extra 两个 Map 结构递归清洗。</p>
     */
    private ChatAnswerBO sanitizeJsonFloats(ChatAnswerBO ans) {
        if (ans == null) {
            return null;
        }
        if (ans.getReference() != null) {
            ans.setReference(sanitizeMap(ans.getReference()));
        }
        if (ans.getExtra() != null) {
            ans.setExtra(sanitizeMap(ans.getExtra()));
        }
        return ans;
    }

    /**
     * 递归清洗任意对象中的 NaN/Infinity 浮点值（对应 _sanitize_json_floats 的递归主体）。
     */
    private Object sanitizeValue(Object obj) {
        if (obj == null) {
            return null;
        }
        // 对应 Python: try math.isnan/isinf -> return None
        if (obj instanceof Float f) {
            return (f.isNaN() || f.isInfinite()) ? null : obj;
        }
        if (obj instanceof Double d) {
            return (d.isNaN() || d.isInfinite()) ? null : obj;
        }
        if (obj instanceof Map<?, ?> map) {
            return sanitizeMap(map);
        }
        if (obj instanceof java.util.List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(sanitizeValue(item));
            }
            return result;
        }
        return obj;
    }

    /**
     * 递归清洗 Map 中的 NaN/Infinity 浮点值（对应字典分支）。
     */
    private Map<String, Object> sanitizeMap(Map<?, ?> map) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
        }
        return result;
    }

    // ==================== 消息规范化 ====================

    /**
     * 规范化消息列表（对应 _normalize_completion_messages 的校验部分）。
     *
     * <p>支持 messages 或 question 两种入参；校验 role/content 必填；
     * 返回未裁剪的完整消息列表。</p>
     * 功能：构建一个清洗后的新列表 msg。
     * <p>
     * 规则 1：跳过 system 消息。过滤掉系统提示词。
     * <p>
     * 规则 2：跳过开头的 assistant 消息。如果 msg 还是空的（即对话刚开始），但第一条消息是 AI 回复的，直接丢弃（因为对话不能由 AI 凭空开始）。
     */
    private List<ChatMessageBO> normalizeMessages(ChatCompletionReqBO req) {
        List<ChatMessageBO> messages = req.getMessages();
        // 1. 基础校验：messages 必须存在且不为空
        if (CollectionUtils.isEmpty(messages)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`messages` must be a non-empty list.");
        }
        // 2. 遍历校验每个 message 的合法性
        for (ChatMessageBO m : messages) {
            if (m == null || StringUtils.isBlank(m.getRole()) || StringUtils.isBlank(m.getContent())) {
                throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                        "Every item in `messages` must include `role` and `content`.");
            }
        }
        return messages;
    }

    /**
     * 裁剪消息（对应 _normalize_completion_messages 中构造 msg 的逻辑）：
     * 功能：构建一个清洗后的新列表 msg。去除system，第一条assistant消息，然后强制最后一条message是user，并补充message的Id
     */
    private List<ChatMessageBO> trimMessages(List<ChatMessageBO> messages) {
        List<ChatMessageBO> msg = new ArrayList<>();
        for (ChatMessageBO m : messages) {
            // 跳过 system 消息。过滤掉系统提示词。
            if ("system".equals(m.getRole())) {
                continue;
            }
            // 跳过开头的 assistant 消息。如果 msg 还是空的（即对话刚开始），但第一条消息是 AI 回复的，直接丢弃（因为对话不能由 AI 凭空开始）。
            if ("assistant".equals(m.getRole()) && CollectionUtils.isEmpty(msg)) {
                continue;
            }
            msg.add(m);
        }
        // 确保清洗后的对话里至少有一条消息。
        if (CollectionUtils.isEmpty(msg)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`messages` must contain a user message.");
        }
        ChatMessageBO lastMessage = msg.getLast();
        // 强制要求最后一条消息必须是 user 发送的（因为大模型需要基于用户的最新提问来回复）。
        if (!"user".equals(lastMessage.getRole())) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "The last message must be from user.");
        }

        // 5. 补全最后一条消息的 ID。如果最后一条用户消息没有 id，调用 get_uuid() 函数为其自动生成一个全球唯一标识符（UUID）。
        // Java 对象是引用传递，这里修改了 msg 中的元素，req.getMessages() 对应的元素也会同步改变
        if (StringUtils.isBlank(lastMessage.getId())) {
            lastMessage.setId(UUID.randomUUID().toString());
        }
        return msg;
    }

    // ==================== 模型配置 ====================

    /**
     * 应用模型与生成配置（对应 session_completion 中 chat_model_id / 默认模型分支）。
     *
     * <p>严格对齐 RagFlow：
     * <ul>
     *   <li>指定了 llm_id：校验可用性（get_api_key），设置 dia.llm_id 并直接用 generationConfig 覆盖 llm_setting；</li>
     *   <li>未指定 llm_id 且 dia 无 llm_id：使用租户默认模型（无则抛错），并 merge generationConfig；</li>
     *   <li>其余情况（dia 已有 llm_id）：不做任何生成配置合并（与 RagFlow 无 else 分支保持一致）。</li>
     * </ul></p>
     */
    private void applyModelConfig(CompletionContext ctx, ChatCompletionReqBO req,
                                  String userId, Map<String, Object> generationConfig) {
        String chatModelId = req.getLlmId();
        if (StringUtils.isNotBlank(chatModelId)) {
            // 对应 Python: 校验 get_api_key（模型服务接入后补充），设置 dia.llm_id / llm_setting
            ctx.dialog.setLlmId(chatModelId);
            ctx.dialog.setLlmSetting(generationConfig);
        } else if (StringUtils.isBlank(ctx.dialog.getLlmId())) {
            // 对应 Python: 使用租户默认模型 -> 无则抛 LookupError -> merge_generation_config
            log.info("empty llm_id in req, use default chat model. userId={}", userId);
            String tenantDefaultLlmId = resolveTenantDefaultLlmId(ctx.dialog.getUserId());
            if (StringUtils.isBlank(tenantDefaultLlmId)) {
                throw new BizException(ResCodeEnum.DATA_NOT_EXIST, "No default chat model for tenant.");
            }
            ctx.dialog.setLlmId(tenantDefaultLlmId);
            mergeGenerationConfig(ctx.dialog, generationConfig);
        }
        // 对应 Python 无 else 分支：dia 已有 llm_id 时不合并生成配置
    }

    /**
     * 合并生成配置到 dialog.llm_setting（对应 merge_generation_config）。
     */
    private void mergeGenerationConfig(DialogBO dialog, Map<String, Object> generationConfig) {
        if (CollectionUtils.isEmpty(generationConfig)) {
            return;
        }
        Map<String, Object> merged = dialog.getLlmSetting() == null
                ? new HashMap<>() : new HashMap<>(dialog.getLlmSetting());
        merged.putAll(generationConfig);
        dialog.setLlmSetting(merged);
    }

    /**
     * 解析默认聊天模型 ID。
     *
     * <p>本系统无租户概念（仅按 userId 使用），当请求与 dialog 均未指定 llm_id 时，
     * 回退到配置文件中 spring.ai.openai.chat.model 定义的默认模型。</p>
     */
    private String resolveTenantDefaultLlmId(String userId) {
        return defaultChatModel;
    }

    // ==================== 会话持久化 ====================

    /**
     * 为补全请求创建新会话（对应 _create_session_for_completion）。
     */
    private ChatConversationPO createSessionForCompletion(Long dialogId, DialogBO dialog, String userId) {
        ChatConversationPO conv = new ChatConversationPO();
        conv.setId(IdWorker.getId());
        conv.setDialogId(dialogId);
        conv.setName("New session");
        conv.setUserId(userId);
        List<ChatMessageBO> initMessages = new ArrayList<>();
        Object prologue = dialog.getPromptConfig() == null ? null : dialog.getPromptConfig().get("prologue");
        initMessages.add(ChatMessageBO.of("assistant", prologue == null ? "" : String.valueOf(prologue)));
        conv.setMessage(toJson(initMessages));
        conv.setReference(toJson(new ArrayList<>()));
        if (!conversationCoreService.save(conv)) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "Fail to create a session!");
        }
        return conv;
    }

    /**
     * 持久化会话（对应 ConversationService.update_by_id(conv.id, conv.to_dict())）。
     */
    private void persistConversation(CompletionContext ctx) {
        if (ctx.conv == null) {
            return;
        }
        ctx.conv.setMessage(toJson(ctx.convMessages));
        ctx.conv.setReference(toJson(ctx.convReference));
        ctx.conv.setModifiedTime(new Date());
        conversationCoreService.updateById(ctx.conv);
    }

    // ==================== 转换与工具 ====================

    /**
     * DialogPO 转运行时 DialogBO。
     */
    private DialogBO toDialogBO(ChatDialogPO po) {
        DialogBO bo = new DialogBO();
        bo.setLlmId(StringUtils.isBlank(po.getLlmId()) ? "" : po.getLlmId());
        bo.setLlmSetting(parseMap(po.getLlmSetting()));
        bo.setPromptConfig(parseMap(po.getPromptConfig()));
        bo.setKbIds(parseStringList(po.getKbIds()));
        bo.setTopN(po.getTopN() == null ? 6 : po.getTopN());
        bo.setTopK(po.getTopK() == null ? 1024 : po.getTopK());
        bo.setRerankId(po.getRerankId() == null ? "" : po.getRerankId());
        bo.setSimilarityThreshold(po.getSimilarityThreshold() == null ? 0.1 : po.getSimilarityThreshold());
        bo.setVectorSimilarityWeight(
                po.getVectorSimilarityWeight() == null ? 0.3 : po.getVectorSimilarityWeight());
        return bo;
    }

    /**
     * 答案对象转 Map（用于 SSE data 序列化，字段名对齐 RagFlow）。
     */
    private Map<String, Object> answerToMap(ChatAnswerBO ans) {
        Map<String, Object> map = new HashMap<>();
        map.put("answer", ans.getAnswer());
        map.put("reference", ans.getReference());
        map.put("id", ans.getId());
        map.put("conv_id", ans.getConvId());
        if (ans.getDialogId() != null) {
            map.put("dialog_id", ans.getDialogId());
        }
        if (ans.getFinalFlag() != null) {
            map.put("final", ans.getFinalFlag());
        }
        if (ans.getStartToThink() != null) {
            map.put("start_to_think", ans.getStartToThink());
        }
        if (ans.getEndToThink() != null) {
            map.put("end_to_think", ans.getEndToThink());
        }
        if (ans.getExtra() != null) {
            map.putAll(ans.getExtra());
        }
        return map;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "JSON 序列化失败: " + e.getMessage());
        }
    }

    private List<ChatMessageBO> parseMessages(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("parse conversation messages failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> parseReference(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("parse conversation reference failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (StringUtils.isBlank(json)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("parse map failed: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private List<Long> parseStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("parse string list failed: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}