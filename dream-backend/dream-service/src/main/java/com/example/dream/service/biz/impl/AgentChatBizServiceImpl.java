package com.example.dream.service.biz.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.example.dream.common.enums.base.ResCodeEnum;
import com.example.dream.common.exception.BizException;
import com.example.dream.dal.po.ChatConversationPO;
import com.example.dream.dal.po.ChatDialogPO;
import com.example.dream.service.biz.AgentChatBizService;
import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.service.core.ConversationCoreService;
import com.example.dream.service.core.DialogCoreService;
import com.example.dream.service.core.ai.agent.AgentLoop;
import com.example.dream.service.core.ai.agent.hook.AgentCallbacks;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.agent.message.OutboundMessage;
import com.example.dream.service.core.ai.agent.session.AgentSession;
import com.example.dream.service.core.ai.agent.session.AgentSessionManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link AgentChatBizService} 实现：用 Spring AI 2.0 重写的 nanobot
 * {@code AgentLoop.process_direct()} 直连入口，对接前端 AI 数据库。
 *
 * <p>编排流程（同步 REST/SSE 直连，无消息总线）：
 * 消息规范化 → dialog/conv 归属校验 → 从 DB 会话装配历史到 {@link AgentSession}
 * → 调用 {@link AgentLoop} 运行 Agent 回合（模型 + 工具循环）→ 结构化答案
 * → 会话持久化到 {@code chat_conversation}。</p>
 *
 * <p>会话桥接策略：每次请求以 {@code convId} 作为 AgentLoop 的 sessionKey，
 * 请求前用 DB 中的历史重建内存会话（保证与 DB 一致），回合结束后把新产生的
 * assistant 回复回写 DB。这样既复用了 AgentLoop 的完整流程，又以前端会话表为准。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatBizServiceImpl implements AgentChatBizService {

    private final DialogCoreService dialogCoreService;
    private final ConversationCoreService conversationCoreService;
    private final AgentLoop agentLoop;
    private final AgentSessionManager agentSessionManager;
    private final ObjectMapper objectMapper;

    /**
     * 系统默认聊天模型 key，取自 spring.ai.openai.chat.model。
     */
    @Value("${spring.ai.openai.chat.model:}")
    private String defaultChatModel;

    /**
     * 本轮编排上下文，聚合会话/消息等中间态。
     */
    private static class AgentContext {
        ChatConversationPO conv;
        Long dialogId;
        Long convId;
        String messageId;
        String modelKey;
        String userQuestion;
        List<ChatMessageBO> convMessages;
    }

    @Override
    public Flux<String> streamCompletion(ChatCompletionReqBO req, String userId) {
        AgentContext ctx = prepare(req, userId);
        // Spring AI 2.0 响应式写法：用 Sinks 把 AgentLoop 的回调式 delta 桥接为 Flux。
        // 前端协议要求每帧推送「累积全文」，AgentLoop 的 onStream 回调是增量 delta，此处累积。
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        StringBuilder accumulated = new StringBuilder();

        // AgentLoop 是同步阻塞状态机，放到弹性调度器执行，避免阻塞事件循环/容器线程。
        Schedulers.boundedElastic().schedule(() -> {
            try {
                AgentCallbacks callbacks = new AgentCallbacks(
                        null,
                        delta -> {
                            accumulated.append(delta);
                            sink.tryEmitNext(emitChunk(ctx, accumulated.toString(), false));
                        },
                        null,
                        null);

                OutboundMessage outbound = agentLoop.processStream(
                        ctx.userQuestion, sessionKey(ctx), ctx.modelKey, callbacks);

                String finalAnswer = outbound == null ? "" : outbound.getContent();
                if (StringUtils.isBlank(accumulated) && StringUtils.isNotBlank(finalAnswer)) {
                    // 非流式兜底：模型未走 delta（如工具循环后一次性产出），补推一帧全文
                    sink.tryEmitNext(emitChunk(ctx, finalAnswer, false));
                    accumulated.append(finalAnswer);
                }

                // 回写会话并推送结束帧
                appendAssistantMessage(ctx, accumulated.toString());
                persistConversation(ctx);
                sink.tryEmitNext(emitChunk(ctx, "", true));
            } catch (Exception ex) {
                log.error("[AgentChat] 流式处理异常, convId={}", ctx.convId, ex);
                Map<String, Object> data = new HashMap<>();
                data.put("answer", "**ERROR**: " + ex.getMessage());
                data.put("reference", new HashMap<>());
                Map<String, Object> err = new HashMap<>();
                err.put("code", 500);
                err.put("message", ex.getMessage());
                err.put("data", data);
                sink.tryEmitNext(toJson(err));
            } finally {
                // 兼容旧协议结束标记：data:true
                Map<String, Object> done = new HashMap<>();
                done.put("code", 0);
                done.put("message", "");
                done.put("data", Boolean.TRUE);
                sink.tryEmitNext(toJson(done));
                sink.tryEmitComplete();
            }
        });

        return sink.asFlux();
    }

    @Override
    public ChatAnswerBO completion(ChatCompletionReqBO req, String userId) {
        AgentContext ctx = prepare(req, userId);
        OutboundMessage outbound = agentLoop.processDirect(
                ctx.userQuestion, sessionKey(ctx), ctx.modelKey);
        String answer = outbound == null ? "" : outbound.getContent();

        appendAssistantMessage(ctx, answer);
        persistConversation(ctx);
        return buildAnswer(ctx, answer);
    }

    // ==================== 准备阶段 ====================

    /**
     * 准备阶段：消息规范化、归属校验、从 DB 会话重建内存会话历史。
     */
    private AgentContext prepare(ChatCompletionReqBO req, String userId) {
        AgentContext ctx = new AgentContext();

        // 1. 规范化消息，取最后一条 user 提问
        List<ChatMessageBO> messages = normalizeMessages(req);
        ChatMessageBO lastUser = messages.getLast();
        if (!"user".equals(lastUser.getRole())) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "The last message must be from user.");
        }
        if (StringUtils.isBlank(lastUser.getId())) {
            lastUser.setId(UUID.randomUUID().toString());
        }
        ctx.messageId = lastUser.getId();
        ctx.userQuestion = lastUser.getContent();

        ctx.dialogId = req.getDialogId();
        ctx.convId = req.getConvId();

        // 2. 模型 key 解析：请求指定优先，否则默认模型（为空则用 AgentLoop 默认）
        ctx.modelKey = StringUtils.isNotBlank(req.getLlmId()) ? req.getLlmId()
                : (StringUtils.isNotBlank(defaultChatModel) ? defaultChatModel : null);

        // 3. dialog/conv 校验与会话装配
        if (ctx.convId != null && ctx.dialogId == null) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                    "`dialog_id` is required when `conversation_id` is provided.");
        }
        if (ctx.dialogId != null) {
            ChatDialogPO dialog = dialogCoreService.getOwnedValidDialog(ctx.dialogId, userId);
            if (dialog == null) {
                throw new BizException(ResCodeEnum.UNAUTHORIZED, "No authorization.");
            }
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
                ctx.conv = createSession(ctx.dialogId, userId);
                ctx.convId = ctx.conv.getId();
            }
            ctx.convMessages = parseMessages(ctx.conv.getMessage());
        } else {
            // 无会话：仅直连补全，不落库
            ctx.convMessages = new ArrayList<>();
        }

        // 4. 把 DB 历史桥接进 AgentLoop 的内存会话（以 DB 为准，重建后追加当前用户消息）
        rebuildAgentSession(ctx);
        return ctx;
    }

    /**
     * 用 DB 会话历史重建 AgentLoop 内存会话，保证 AgentLoop 看到的上下文与前端 DB 一致。
     * <p>清空后按 DB 历史逐条注入（跳过孤立的空消息），不含本轮当前用户消息
     * （当前用户消息由 AgentLoop.processXxx 的 content 参数注入）。</p>
     */
    private void rebuildAgentSession(AgentContext ctx) {
        String key = sessionKey(ctx);
        AgentSession session = agentSessionManager.getOrCreate(key);
        session.clear();
        if (ctx.convMessages != null) {
            for (ChatMessageBO m : ctx.convMessages) {
                if (m == null || StringUtils.isBlank(m.getRole()) || StringUtils.isBlank(m.getContent())) {
                    continue;
                }
                if ("system".equals(m.getRole())) {
                    continue;
                }
                session.addMessage(AgentMessage.of(m.getRole(), m.getContent()));
            }
        }
        agentSessionManager.save(session);
    }

    // ==================== 会话持久化 ====================

    /**
     * 追加/更新会话中的用户与 assistant 消息（对齐前端会话消息结构）。
     */
    private void appendAssistantMessage(AgentContext ctx, String answer) {
        if (ctx.conv == null) {
            return;
        }
        if (ctx.convMessages == null) {
            ctx.convMessages = new ArrayList<>();
        }
        double now = System.currentTimeMillis() / 1000.0;

        // 追加本轮用户消息
        ChatMessageBO user = ChatMessageBO.of("user", ctx.userQuestion);
        user.setId(ctx.messageId);
        user.setCreatedAt(now);
        ctx.convMessages.add(user);

        // 追加 assistant 回复
        ChatMessageBO assistant = ChatMessageBO.of("assistant", answer == null ? "" : answer);
        assistant.setId(ctx.messageId);
        assistant.setCreatedAt(now);
        ctx.convMessages.add(assistant);
    }

    /**
     * 持久化会话到 chat_conversation 表。
     */
    private void persistConversation(AgentContext ctx) {
        if (ctx.conv == null) {
            return;
        }
        ctx.conv.setMessage(toJson(ctx.convMessages));
        ctx.conv.setModifiedTime(new Date());
        conversationCoreService.updateById(ctx.conv);
        log.info("[AgentChat] 会话已持久化, convId={}", ctx.conv.getId());
    }

    /**
     * 为补全请求创建新会话。
     */
    private ChatConversationPO createSession(Long dialogId, String userId) {
        ChatConversationPO conv = new ChatConversationPO();
        conv.setId(IdWorker.getId());
        conv.setDialogId(dialogId);
        conv.setName("New session");
        conv.setUserId(userId);
        conv.setMessage(toJson(new ArrayList<>()));
        conv.setReference(toJson(new ArrayList<>()));
        if (!conversationCoreService.save(conv)) {
            throw new BizException(ResCodeEnum.SERVER_ERROR, "Fail to create a session!");
        }
        return conv;
    }

    // ==================== SSE 输出 ====================

    /**
     * 构造一帧 SSE 数据体（对齐前端协议），返回其 JSON 字符串。
     *
     * @param answer  累积全文（final 帧为空串）
     * @param isFinal 是否结束帧
     * @return SSE data 的 JSON 字符串
     */
    private String emitChunk(AgentContext ctx, String answer, boolean isFinal) {
        Map<String, Object> data = new HashMap<>();
        data.put("answer", answer);
        data.put("reference", new HashMap<>());
        data.put("id", ctx.messageId);
        data.put("conv_id", ctx.convId);
        if (ctx.dialogId != null) {
            data.put("dialog_id", ctx.dialogId);
        }
        data.put("final", isFinal);
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", 0);
        payload.put("message", "");
        payload.put("data", data);
       return toJson(payload);
    }

    /**
     * 构建非流式答案对象。
     */
    private ChatAnswerBO buildAnswer(AgentContext ctx, String answer) {
        ChatAnswerBO bo = new ChatAnswerBO();
        bo.setAnswer(answer);
        bo.setReference(new HashMap<>());
        bo.setId(ctx.messageId);
        bo.setConvId(ctx.convId);
        bo.setDialogId(ctx.dialogId);
        bo.setFinalFlag(Boolean.TRUE);
        return bo;
    }

    // ==================== 工具方法 ====================

    /**
     * 会话 key：优先用 convId，否则用临时 key（无会话直连场景）。
     */
    private String sessionKey(AgentContext ctx) {
        return ctx.convId != null ? "agent:conv:" + ctx.convId : "agent:tmp:" + ctx.messageId;
    }

    /**
     * 消息规范化：校验非空与 role/content 必填。
     */
    private List<ChatMessageBO> normalizeMessages(ChatCompletionReqBO req) {
        List<ChatMessageBO> messages = req.getMessages();
        if (CollectionUtils.isEmpty(messages)) {
            throw new BizException(ResCodeEnum.PARAMETER_ERROR, "`messages` must be a non-empty list.");
        }
        for (ChatMessageBO m : messages) {
            if (m == null || StringUtils.isBlank(m.getRole()) || StringUtils.isBlank(m.getContent())) {
                throw new BizException(ResCodeEnum.PARAMETER_ERROR,
                        "Every item in `messages` must include `role` and `content`.");
            }
        }
        return messages;
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
}