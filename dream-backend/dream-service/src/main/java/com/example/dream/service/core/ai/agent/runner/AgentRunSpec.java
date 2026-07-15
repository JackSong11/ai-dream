package com.example.dream.service.core.ai.agent.runner;

import com.example.dream.service.core.ai.agent.hook.AgentCallbacks;
import com.example.dream.service.core.ai.agent.hook.AgentHook;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行规范，对应 nanobot agent.runner.AgentRunSpec。
 * <p>
 * 聚合一次 Agent 迭代循环所需的全部输入：初始消息、模型 key、工具集、
 * 迭代上限、回调与钩子等。由 {@link AgentRunner} 消费。
 *
 * @author dream
 */
@Data
@Builder
public class AgentRunSpec {

    /**
     * 初始消息列表（system + 历史 + 当前用户消息）。
     */
    @Builder.Default
    private List<AgentMessage> initialMessages = new ArrayList<>();

    /**
     * 模型 key，路由到 ChatModelRegistry 对应的 ChatClient；为空使用默认模型。
     */
    private String modelKey;

    /**
     * 工具对象数组（带 @Tool 注解的方法所在对象），交由模型自主决定调用。
     */
    @Builder.Default
    private Object[] tools = new Object[0];

    /**
     * 最大工具迭代次数（Spring AI 内部循环上限的业务侧约束参考）。
     */
    @Builder.Default
    private int maxIterations = 10;

    /**
     * 是否流式输出。
     */
    @Builder.Default
    private boolean stream = false;

    /**
     * 回调集合。
     */
    @Builder.Default
    private AgentCallbacks callbacks = AgentCallbacks.empty();

    /**
     * 回合钩子列表。
     */
    @Builder.Default
    private List<AgentHook> hooks = new ArrayList<>();

    /**
     * 会话 key（用于日志与观测）。
     */
    private String sessionKey;
}