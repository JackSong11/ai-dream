package com.example.dream.web.vo.ai;

import lombok.Data;
import org.springframework.util.CollectionUtils;

import java.util.List;

@Data
public class AgentCompletionReqVO {
    private Long dialogId;
    private Long convId;
    private String modelKey;
    private List<InputMessage> messages;

    @Data
    public static class InputMessage {
        private String role;
        private String content;
    }

    public String latestUserText() {
        if (CollectionUtils.isEmpty(messages)) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            InputMessage message = messages.get(i);
            if ("user".equals(message.getRole())) return message.getContent();
        }
        return null;
    }
}
