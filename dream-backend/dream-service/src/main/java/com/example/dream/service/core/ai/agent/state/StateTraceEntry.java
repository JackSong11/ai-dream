package com.example.dream.service.core.ai.agent.state;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 状态执行轨迹条目，对应 nanobot 的 StateTraceEntry。
 * <p>
 * 记录每个状态处理器的执行耗时、返回事件与异常，用于调试与观测。
 *
 * @author dream
 */
@Data
@AllArgsConstructor
public class StateTraceEntry {

    /**
     * 执行的状态。
     */
    private TurnState state;

    /**
     * 起始时间（纳秒，System.nanoTime()）。
     */
    private long startedAtNanos;

    /**
     * 执行耗时（毫秒）。
     */
    private double durationMs;

    /**
     * 处理器返回的事件字符串（用于状态转移查表）。
     */
    private String event;

    /**
     * 异常信息，正常执行时为 null。
     */
    private String error;

    public StateTraceEntry(TurnState state, long startedAtNanos, double durationMs, String event) {
        this(state, startedAtNanos, durationMs, event, null);
    }
}