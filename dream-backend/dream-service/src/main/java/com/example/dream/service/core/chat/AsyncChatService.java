package com.example.dream.service.core.chat;

import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatMessageBO;
import com.example.dream.service.biz.bo.chat.DialogBO;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 聊天生成核心领域服务。
 *
 * <p>对应 RagFlow dialog_service.async_chat（RAG 检索 + LLM 流式生成）。
 * 采用回调（Consumer）方式将逐块生成结果推送给上层，以适配 SSE 流式输出，
 * 等价于 Python async generator 的逐次 yield。</p>
 *
 * @author dream
 */
public interface AsyncChatService {

    /**
     * 执行一轮对话生成（RAG 检索 + LLM 生成）。
     *
     * <p>对应 RagFlow: async for ans in async_chat(dia, msg, stream, conversation_id=..., **req)。
     * 每产出一个分块（或最终结果）即回调一次 chunkConsumer。</p>
     *
     * @param dialog        对话运行时配置（对应 dia）
     * @param messages      本轮送入模型的消息列表（对应 msg）
     * @param stream        是否流式生成（对应 stream）
     * @param convId     会话 ID（对应 conversation_id）
     * @param extraParams   其余透传参数（对应 **req）
     * @param chunkConsumer 分块结果回调（对应每次 yield ans）
     */
    void asyncChat(DialogBO dialog,
                   List<ChatMessageBO> messages,
                   boolean stream,
                   Long convId,
                   Map<String, Object> extraParams,
                   Consumer<ChatAnswerBO> chunkConsumer);
}