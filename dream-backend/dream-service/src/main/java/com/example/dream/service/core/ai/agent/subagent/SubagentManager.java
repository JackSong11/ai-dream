package com.example.dream.service.core.ai.agent.subagent;

import com.example.dream.service.core.ai.agent.hook.AgentCallbacks;
import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.agent.runner.AgentRunResult;
import com.example.dream.service.core.ai.agent.runner.AgentRunSpec;
import com.example.dream.service.core.ai.agent.runner.AgentRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 子智能体管理器，对应 nanobot agent.subagent.SubagentManager。
 * <p>
 * <b>核心引擎与主 Agent 一致（复用 {@link AgentRunner}），但去掉消息总线：</b>
 * <ul>
 *   <li>子任务用 JDK 21 虚拟线程执行，不阻塞主 Agent 线程；</li>
 *   <li>结果通过 <b>同步返回值 / Future</b> 直接交回主 Agent，
 *       而非 nanobot 那样包成 system 消息经队列回注（mid-turn 注入）；
 *       在同步直连架构下，主 Agent 调用 {@link #runBlocking} 拿到结果后
 *       直接拼进对话上下文，更直观；</li>
 *   <li>并发上限由 {@link #maxConcurrent} 控制，对应 max_concurrent_subagents。</li>
 * </ul>
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubagentManager {

    private final AgentRunner runner;

    /**
     * 子任务专用虚拟线程池（每任务一线程，JDK 21）。
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 当前运行中的子任务数。
     */
    private final AtomicInteger running = new AtomicInteger(0);

    /**
     * 子任务状态表：taskId -> 状态。
     */
    private final ConcurrentHashMap<String, SubagentStatus> statuses = new ConcurrentHashMap<>();

    /**
     * 最大并发子任务数，对应 nanobot max_concurrent_subagents。
     */
    private volatile int maxConcurrent = 5;

    /**
     * 子智能体系统提示词模板。
     */
    private static final String SUBAGENT_SYSTEM_PROMPT =
            "你是一个专注的子智能体，负责独立完成主智能体交给你的单一任务。"
                    + "请充分利用可用工具，完成后给出简洁明确的最终结论。";

    /**
     * 同步执行一个子任务并返回结果（供主 Agent 在工具调用中直接拿到结果）。
     * <p>
     * 这是替代 nanobot「结果经队列回注」的核心方法：主 Agent 的 SpawnTool
     * 直接调用它，拿到子 Agent 的最终回复后拼进主对话。
     *
     * @param task     任务描述
     * @param label    显示标签（可空）
     * @param modelKey 子 Agent 使用的模型 key（为空用默认）
     * @param tools    子 Agent 可用的工具对象
     * @return 子 Agent 的最终回复文本
     */
    public String runBlocking(String task, String label, String modelKey, Object... tools) {
        if (running.get() >= maxConcurrent) {
            return String.format("无法启动子智能体：已达并发上限（%d/%d 运行中），请等待其他子任务完成。",
                    running.get(), maxConcurrent);
        }
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String display = label != null ? label
                : task.length() > 30 ? task.substring(0, 30) + "..." : task;
        SubagentStatus status = new SubagentStatus(taskId, display, task);
        statuses.put(taskId, status);
        running.incrementAndGet();
        log.info("[SubagentManager] 启动子智能体 [{}]: {}", taskId, display);

        try {
            // 虚拟线程执行，主线程阻塞等待结果（同步返回语义）
            CompletableFuture<AgentRunResult> future = CompletableFuture.supplyAsync(
                    () -> execute(task, modelKey, tools), executor);
            AgentRunResult result = future.join();
            status.setPhase("done");
            status.setStopReason(result.getStopReason());

            if ("error".equals(result.getStopReason())) {
                String err = result.getFinalContent() != null
                        ? result.getFinalContent() : "子智能体执行失败。";
                log.warn("[SubagentManager] 子智能体 [{}] 执行出错: {}", taskId, err);
                return "子智能体 [" + display + "] 执行失败：" + err;
            }
            String finalResult = result.getFinalContent() != null && !result.getFinalContent().isBlank()
                    ? result.getFinalContent() : "任务已完成，但未生成最终回复。";
            log.info("[SubagentManager] 子智能体 [{}] 成功完成", taskId);
            return "子智能体 [" + display + "] 已完成，结果如下：\n" + finalResult;
        } catch (Exception e) {
            status.setPhase("error");
            status.setError(e.getMessage());
            log.error("[SubagentManager] 子智能体 [{}] 异常", taskId, e);
            return "子智能体 [" + display + "] 执行异常：" + e.getMessage();
        } finally {
            running.decrementAndGet();
            statuses.remove(taskId);
        }
    }

    /**
     * 异步启动子任务并立即返回 Future（供需要非阻塞并行的场景）。
     *
     * @return 承载最终回复文本的 Future
     */
    public CompletableFuture<String> runAsync(String task, String label, String modelKey, Object... tools) {
        return CompletableFuture.supplyAsync(() -> runBlocking(task, label, modelKey, tools), executor);
    }

    /**
     * 真正执行子 Agent 迭代循环（复用主 Agent 的 AgentRunner）。
     */
    private AgentRunResult execute(String task, String modelKey, Object... tools) {
        List<AgentMessage> messages = List.of(
                AgentMessage.of("system", SUBAGENT_SYSTEM_PROMPT),
                AgentMessage.of("user", task));
        AgentRunSpec spec = AgentRunSpec.builder()
                .initialMessages(messages)
                .modelKey(modelKey)
                .tools(tools == null ? new Object[0] : tools)
                .stream(false)
                .callbacks(AgentCallbacks.empty())
                .hooks(List.of())
                .sessionKey("subagent")
                .build();
        return runner.run(spec);
    }

    /**
     * 当前运行中的子任务数。
     */
    public int getRunningCount() {
        return running.get();
    }

    /**
     * 最大并发上限。
     */
    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public void setMaxConcurrent(int maxConcurrent) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
    }

    /**
     * 子智能体实时状态，对应 nanobot SubagentStatus。
     */
    @lombok.Data
    public static class SubagentStatus {
        private final String taskId;
        private final String label;
        private final String taskDescription;
        private final long startedAtMs = System.currentTimeMillis();
        /**
         * 阶段：initializing | running | done | error。
         */
        private String phase = "initializing";
        private int iteration = 0;
        private String stopReason;
        private String error;

        public SubagentStatus(String taskId, String label, String taskDescription) {
            this.taskId = taskId;
            this.label = label;
            this.taskDescription = taskDescription;
        }
    }
}