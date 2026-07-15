package com.example.dream.service.core.ai.agent.state;

/**
 * 回合状态枚举，1:1 还原 nanobot AgentLoop 的 TurnState。
 * <p>
 * 事件驱动的状态机：RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE。
 * COMMAND 命中快捷命令时可直接跳到 DONE。
 *
 * @author dream
 */
public enum TurnState {

    /**
     * 恢复检查点 / 未完成的用户回合，提取文档附件。
     */
    RESTORE,

    /**
     * 会话记忆压缩（autocompact）。
     */
    COMPACT,

    /**
     * 命令分发（如 /new、/stop 等），命中快捷命令直接结束。
     */
    COMMAND,

    /**
     * 构建初始消息列表（历史 + 系统提示 + 运行时上下文）。
     */
    BUILD,

    /**
     * 运行 Agent 迭代循环（多轮工具调用）。
     */
    RUN,

    /**
     * 保存本回合消息到会话历史。
     */
    SAVE,

    /**
     * 组装并返回出站消息。
     */
    RESPOND,

    /**
     * 回合结束。
     */
    DONE
}