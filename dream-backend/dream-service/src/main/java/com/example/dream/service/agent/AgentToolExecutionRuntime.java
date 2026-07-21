package com.example.dream.service.agent;

import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Makes Spring AI's internal tool boundary observable by the durable agent runtime.
 * Spring AI still performs provider-specific tool execution; this decorator owns
 * iteration limits and exposes every assistant/tool pair for checkpointing.
 */
@Component
public class AgentToolExecutionRuntime implements ToolCallingManager {
    private final ToolCallingManager delegate = ToolCallingManager.builder().build();
    private final ThreadLocal<Execution> current = new ThreadLocal<>();

    public Scope open(int maxIterations, BoundaryListener listener) {
        if (current.get() != null) throw new IllegalStateException("不支持嵌套Agent执行。");
        current.set(new Execution(maxIterations, listener));
        return current::remove;
    }

    // 请求前：【出】告知可用工具
    // 作用：解析并收集当前应用里有哪些 Java 方法（如标注了 @Tool 的方法），并将它们转换成大模型能够理解的 JSON Schema 格式。
    // 为什么必须有它：大模型不会凭空知道你本地写了什么代码。在把 Prompt 发给 LLM 之前，系统必须通过这个方法把“工具菜单”打包塞进请求头里。
    @NotNull
    @Override
    public List<ToolDefinition> resolveToolDefinitions(@NotNull ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    // 响应后：【入】处理调用结果
    // 作用：当大模型评估后认为需要调用工具，并返回了类似 {"name": "getWeather", "arguments": {"city": "Beijing"}} 的指令时，这个方法会被触发。
    // 为什么必须有它：LLM 只是个“脑子”，它没有权限直接运行你服务器上的代码。这个方法就是那个“手”，负责：
    // 1. 拿到模型传来的方法名与 JSON 参数。
    // 2. 反射调用本地对应的 Java 方法。
    // 3. 把执行结果（比如查询到的天气字符串）包装成 ToolExecutionResult 重新喂回给大模型。
    @NotNull
    @Override
    public ToolExecutionResult executeToolCalls(@NotNull Prompt prompt, @NotNull ChatResponse response) {
        Execution execution = current.get();
        // 1. 如果没有通过 open() 开启上下文，降级使用 Spring AI 默认逻辑
        if (execution == null) return delegate.executeToolCalls(prompt, response);

        // 2. 迭代次数递增与上限检查（防死循环）
        int iteration = ++execution.iteration;
        if (iteration > execution.maxIterations) {
            throw new MaxIterationsExceededException(execution.maxIterations);
        }

        // 3. 提取 LLM 的输出（带有 tool_calls 请求的 AssistantMessage）
        AssistantMessage assistant = Objects.requireNonNull(response.getResult()).getOutput();

        // 4. 事件回调：通知监听器“准备开始调用工具”
        execution.listener.awaiting(iteration, assistant);

        // 5. 委托给底层的 delegate 真正去执行 Java 工具函数
        ToolExecutionResult result = delegate.executeToolCalls(prompt, response);

        // 6. 从返回的历史记录里提取出工具执行结果（ToolResponseMessage）
        ToolResponseMessage toolResponse = result.conversationHistory().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((first, second) -> second).orElse(null);

        // 7. 事件回调：通知监听器“工具执行完毕，这是工具返回的数据”
        execution.listener.completed(iteration, assistant, toolResponse, result.conversationHistory());
        return result;
    }

    private static final class Execution {
        private final int maxIterations;
        private final BoundaryListener listener;
        private int iteration;

        private Execution(int maxIterations, BoundaryListener listener) {
            this.maxIterations = maxIterations;
            this.listener = listener;
        }
    }

    // 状态监听器（核心扩展点！）
    public interface BoundaryListener {

        // 工具执行前：拿到模型的请求（思考过程和工具参数）
        void awaiting(int iteration, AssistantMessage assistant);

        // 工具执行后：拿到工具的执行结果及完整上下文
        void completed(int iteration, AssistantMessage assistant, ToolResponseMessage response,
                       List<Message> conversationHistory);
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static final class MaxIterationsExceededException extends RuntimeException {
        public MaxIterationsExceededException(int max) {
            super("Agent reached max tool iterations: " + max);
        }
    }
}
