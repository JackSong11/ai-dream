package com.example.dream.service.core.ai.agent.hook;

import com.example.dream.service.core.ai.agent.message.AgentMessage;

import java.util.List;

/**
 * Agent 回合钩子，对应 nanobot agent.hook.AgentHook。
 * <p>
 * 允许在一个回合的关键节点（迭代开始/结束、工具调用前后）插入自定义逻辑，
 * 例如埋点、审计、注入额外上下文等。默认方法均为空实现，实现方按需覆盖。
 *
 * @author dream
 */
public interface AgentHook {

    /**
     * 回合开始时触发。
     *
     * @param initialMessages 初始消息列表
     */
    default void onTurnStart(List<AgentMessage> initialMessages) {
    }

    /**
     * 每次 LLM 迭代开始时触发。
     *
     * @param iteration 当前迭代序号（从 1 开始）
     */
    default void onIteration(int iteration) {
    }

    /**
     * 工具调用完成时触发。
     *
     * @param toolName 工具名称
     * @param result   工具执行结果文本
     */
    default void onToolResult(String toolName, String result) {
    }

    /**
     * 回合结束时触发。
     *
     * @param finalContent 最终响应内容
     * @param stopReason   停止原因
     */
    default void onTurnEnd(String finalContent, String stopReason) {
    }
}