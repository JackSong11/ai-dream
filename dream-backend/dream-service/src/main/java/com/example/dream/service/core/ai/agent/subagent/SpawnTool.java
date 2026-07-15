package com.example.dream.service.core.ai.agent.subagent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 子智能体工具，对应 nanobot agent.tools.spawn.SpawnTool。
 * <p>
 * 以 Spring AI {@code @Tool} 形式暴露给主 Agent。主 Agent 在需要把复杂/耗时子任务
 * 独立处理时调用本工具；工具内部委托 {@link SubagentManager} 用虚拟线程执行子 Agent，
 * 并 <b>同步返回子 Agent 的最终结论</b>（无需消息总线回注）——返回值会由框架自动
 * 回填进主 Agent 的对话上下文，等价于 nanobot 的 mid-turn 结果注入。
 * <p>
 * 每次为一个回合构造实例：通过构造器注入 modelKey 与子 Agent 可用工具，
 * 使子 Agent 与主 Agent 使用一致的模型与工具集。
 *
 * @author dream
 */
public class SpawnTool {

    private final SubagentManager manager;

    /**
     * 子 Agent 使用的模型 key（一般与主 Agent 相同）。
     */
    private final String modelKey;

    /**
     * 子 Agent 可用的工具对象（一般为主 Agent 工具的子集，不含 spawn 自身以防递归）。
     */
    private final Object[] subagentTools;

    public SpawnTool(SubagentManager manager, String modelKey, Object... subagentTools) {
        this.manager = manager;
        this.modelKey = modelKey;
        this.subagentTools = subagentTools == null ? new Object[0] : subagentTools;
    }

    @Tool(description = "启动一个子智能体在后台独立处理复杂或耗时的子任务。"
            + "适用于可独立完成、与主对话相对解耦的任务。"
            + "子智能体会完成任务并把最终结论返回给你，你再据此继续。")
    public String spawn(
            @ToolParam(description = "交给子智能体完成的任务描述") String task,
            @ToolParam(description = "任务的简短标签（用于展示，可选）", required = false) String label) {
        if (task == null || task.isBlank()) {
            return "错误：任务描述不能为空。";
        }
        return manager.runBlocking(task, label, modelKey, subagentTools);
    }
}