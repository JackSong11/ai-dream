package com.example.dream.service.core.ai.agent.context;

import com.example.dream.service.core.ai.agent.message.AgentMessage;
import com.example.dream.service.core.ai.agent.session.AgentSession;
import com.example.dream.service.core.ai.registry.ChatModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆合并器，对应 nanobot agent.memory.Consolidator + autocompact。
 * <p>
 * 当会话历史消息数超过阈值时，调用 LLM 将较早的历史压缩为摘要，
 * 保留最近若干条消息，从而控制上下文长度。摘要生成复用
 * {@link ChatModelRegistry} 的默认模型。
 *
 * @author dream
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Consolidator {

    private final ChatModelRegistry registry;

    /**
     * 触发压缩的历史消息数阈值。
     */
    private static final int COMPACT_THRESHOLD = 40;

    /**
     * 压缩后保留的最近消息数。
     */
    private static final int KEEP_RECENT = 20;

    /**
     * 按需压缩会话历史。
     * <p>
     * 消息数未超阈值时不做任何操作；超过时把最早的一批消息压缩成摘要，
     * 累加到会话 summary，并从历史中移除已压缩部分。
     *
     * @param session 会话对象
     * @return true 表示发生了压缩
     */
    public boolean maybeConsolidate(AgentSession session) {
        if (session == null) {
            return false;
        }
        List<AgentMessage> messages = session.getMessages();
        if (messages.size() <= COMPACT_THRESHOLD) {
            return false;
        }

        int compactCount = messages.size() - KEEP_RECENT;
        List<AgentMessage> toCompact = messages.subList(0, compactCount);
        String transcript = toTranscript(toCompact);

        String summary = summarize(session.getSummary(), transcript);
        if (StringUtils.hasText(summary)) {
            session.setSummary(summary);
        }

        // 移除已压缩的历史消息（保留最近 KEEP_RECENT 条）
        toCompact.clear();
        log.info("[Consolidator] 会话 {} 已压缩 {} 条历史消息为摘要", session.getKey(), compactCount);
        return true;
    }

    /**
     * 调用默认模型生成/更新摘要。
     */
    private String summarize(String previousSummary, String transcript) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("请将下列对话内容压缩为简洁的中文摘要，保留关键事实、结论与待办：\n\n");
            if (StringUtils.hasText(previousSummary)) {
                prompt.append("已有摘要：\n").append(previousSummary).append("\n\n新增对话：\n");
            }
            prompt.append(transcript);
            return registry.getClient(null).prompt()
                    .user(prompt.toString())
                    .call()
                    .content();
        } catch (Exception ex) {
            log.warn("[Consolidator] 摘要生成失败，跳过压缩: {}", ex.getMessage());
            return previousSummary;
        }
    }

    /**
     * 将消息列表转换为可读的对话文本。
     */
    private String toTranscript(List<AgentMessage> messages) {
        return messages.stream()
                .map(m -> (m.getRole() == null ? "user" : m.getRole())
                        + ": " + (m.getContent() == null ? "" : m.getContent()))
                .collect(Collectors.joining("\n"));
    }
}