package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;

import java.util.function.Consumer;

/**
 * 聊天补全业务编排服务。
 *
 * <p>对应 RagFlow chat_api.py 的 session_completion：
 * 处理流式 / 非流式聊天补全请求，完成消息规范化、会话与对话校验、
 * 生成配置合并、调用 async_chat 生成、会话持久化等编排。</p>
 *
 * @author dream
 */
public interface ChatCompletionBizService {

    /**
     * 流式聊天补全。
     *
     * <p>对应 session_completion 中 stream_mode=True 分支：逐块生成并推送。
     * 每一条 SSE 数据体（JSON 字符串，不含 "data:" 前缀与换行）通过 payloadConsumer 回调，
     * 由 Web 层负责封装为标准 SSE 事件。</p>
     *
     * @param req             请求业务对象
     * @param userId          当前登录用户 ID（对应 RagFlow current_user.id）
     * @param payloadConsumer SSE 数据体消费者（每条为 JSON 字符串）
     */
    void streamCompletion(ChatCompletionReqBO req, String userId, Consumer<String> payloadConsumer);

    /**
     * 非流式聊天补全。
     *
     * <p>对应 session_completion 中 stream_mode=False 分支：一次性返回完整结果。</p>
     *
     * @param req    请求业务对象
     * @param userId 当前登录用户 ID
     * @return 结构化后的完整答案
     */
    ChatAnswerBO completion(ChatCompletionReqBO req, String userId);
}