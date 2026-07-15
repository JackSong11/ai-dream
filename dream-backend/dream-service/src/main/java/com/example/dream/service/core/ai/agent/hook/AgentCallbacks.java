package com.example.dream.service.core.ai.agent.hook;

/**
 * 回合执行期间的回调集合，对应 nanobot AgentLoop 中的
 * on_progress / on_stream / on_stream_end / on_retry_wait 回调族。
 * <p>
 * 采用函数式接口聚合，便于在不同渠道（CLI / SSE / WebSocket）下注入不同实现。
 * 所有回调均可为空实现（NO_OP），Agent 内部调用前会判空。
 *
 * @author dream
 */
public class AgentCallbacks {

    /**
     * 进度回调：工具执行、迭代推进等阶段性通知。
     */
    @FunctionalInterface
    public interface OnProgress {
        void accept(String message);
    }

    /**
     * 流式增量回调：每收到一个内容 delta 时触发。
     */
    @FunctionalInterface
    public interface OnStream {
        void accept(String delta);
    }

    /**
     * 流式结束回调。
     *
     * @param resuming true 表示后续还有工具调用（应重启 spinner），false 表示最终响应结束
     */
    @FunctionalInterface
    public interface OnStreamEnd {
        void accept(boolean resuming);
    }

    /**
     * 重试等待回调：Provider 触发限流/重试等待时通知。
     */
    @FunctionalInterface
    public interface OnRetryWait {
        void accept(String message);
    }

    private final OnProgress onProgress;
    private final OnStream onStream;
    private final OnStreamEnd onStreamEnd;
    private final OnRetryWait onRetryWait;

    public AgentCallbacks(OnProgress onProgress, OnStream onStream,
                          OnStreamEnd onStreamEnd, OnRetryWait onRetryWait) {
        this.onProgress = onProgress;
        this.onStream = onStream;
        this.onStreamEnd = onStreamEnd;
        this.onRetryWait = onRetryWait;
    }

    public static AgentCallbacks empty() {
        return new AgentCallbacks(null, null, null, null);
    }

    public void progress(String message) {
        if (onProgress != null) {
            onProgress.accept(message);
        }
    }

    public void stream(String delta) {
        if (onStream != null) {
            onStream.accept(delta);
        }
    }

    public void streamEnd(boolean resuming) {
        if (onStreamEnd != null) {
            onStreamEnd.accept(resuming);
        }
    }

    public void retryWait(String message) {
        if (onRetryWait != null) {
            onRetryWait.accept(message);
        }
    }

    public boolean hasStream() {
        return onStream != null;
    }
}