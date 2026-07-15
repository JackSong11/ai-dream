package com.example.dream.service.core.ai.agent.context;

import com.example.dream.service.core.ai.agent.message.AgentMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文构建器，对应 nanobot agent.context.ContextBuilder。
 * <p>
 * 负责把「系统提示 + 会话摘要 + 历史消息 + 当前用户消息」拼装成一次
 * LLM 调用所需的初始消息列表。系统提示中注入当前时间等运行时信息。
 *
 * @author dream
 */
@Component
public class ContextBuilder {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 构建初始消息列表。
     *
     * @param systemPrompt   系统提示词（可为空，为空时使用默认）
     * @param sessionSummary 会话摘要（记忆压缩产物，可为空）
     * @param history        历史消息
     * @param currentMessage 当前用户消息内容
     * @param timezone       时区（如 Asia/Shanghai），为空用系统默认
     * @return 组装后的初始消息列表
     */
    public List<AgentMessage> buildMessages(String systemPrompt,
                                            String sessionSummary,
                                            List<AgentMessage> history,
                                            String currentMessage,
                                            String timezone) {
        List<AgentMessage> messages = new ArrayList<>();

        // 1. 系统提示（含运行时信息）
        String sys = buildSystemPrompt(systemPrompt, timezone);
        messages.add(AgentMessage.of("system", sys));

        // 2. 会话摘要（作为额外 system 背景）
        if (StringUtils.hasText(sessionSummary)) {
            messages.add(AgentMessage.of("system", "对话历史摘要：\n" + sessionSummary));
        }

        // 3. 历史消息
        if (history != null) {
            messages.addAll(history);
        }

        // 4. 当前用户消息
        if (StringUtils.hasText(currentMessage)) {
            messages.add(AgentMessage.of("user", currentMessage));
        }

        return messages;
    }

    /**
     * 构建系统提示，注入当前时间。
     */
    private String buildSystemPrompt(String systemPrompt, String timezone) {
        String base = StringUtils.hasText(systemPrompt)
                ? systemPrompt
                : "你是一个乐于助人的智能助手，可在需要时调用可用工具完成任务。";
        ZoneId zone = resolveZone(timezone);
        String now = ZonedDateTime.now(zone).format(TIME_FMT);
        return base + "\n\n当前时间：" + now + "（" + zone.getId() + "）";
    }

    private ZoneId resolveZone(String timezone) {
        if (StringUtils.hasText(timezone)) {
            try {
                return ZoneId.of(timezone);
            } catch (Exception ignored) {
                // 回退到系统默认
            }
        }
        return ZoneId.systemDefault();
    }
}