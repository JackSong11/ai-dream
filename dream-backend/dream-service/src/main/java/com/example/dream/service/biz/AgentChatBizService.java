package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.chat.ChatAnswerBO;
import com.example.dream.service.biz.bo.chat.ChatCompletionReqBO;
import reactor.core.publisher.Flux;

/**
 * 基于 Agent 的聊天补全业务编排服务。
 *
 * <p>用 Spring AI 2.0 重写的 nanobot {@code AgentLoop.process_direct()} 直连入口，
 * 通过 {@link com.example.dream.service.core.ai.agent.AgentLoop} 承载「构建上下文 →
 * 多轮工具调用循环 → 产出最终回复」的核心流程，并将会话历史桥接到前端 AI 数据库
 * （{@code chat_conversation} 表），对齐现有前端 SSE 协议，作为
 * {@link ChatCompletionBizService} 之外的独立新入口。</p>
 *
 * <p>与 {@link ChatCompletionBizService} 的区别：本服务不做 RAG 检索编排，
 * 而是走 Agent 的模型 + 工具循环（同步 REST/SSE 直连，无消息总线）。</p>
 *
 * @author dream
 */
public interface AgentChatBizService {

    /**
     * 流式聊天补全（Agent 直连，Spring AI 2.0 响应式写法）。
     *
     * <p>对应 nanobot {@code process_direct(on_stream=...)}：逐 delta 生成并推送。
     * 返回 {@link Flux}，每个元素为一条 SSE 数据体（JSON 字符串，不含 "data:" 前缀），
     * 由 Web 层封装为 {@code ServerSentEvent} 下发。协议与前端一致：
     * {@code {"code":0,"data":{"answer":累积全文,"reference":{},"final":false}}}，
     * 结束帧 {@code {"code":0,"data":{"answer":"","final":true,...}}}。</p>
     *
     * @param req    请求业务对象
     * @param userId 当前登录用户 ID
     * @return SSE 数据体的响应式流
     */
    Flux<String> streamCompletion(ChatCompletionReqBO req, String userId);

    /**
     * 非流式聊天补全（Agent 直连）。
     *
     * <p>对应 nanobot {@code process_direct()}：一次性返回完整结果。</p>
     *
     * @param req    请求业务对象
     * @param userId 当前登录用户 ID
     * @return 结构化后的完整答案
     */
    ChatAnswerBO completion(ChatCompletionReqBO req, String userId);
}