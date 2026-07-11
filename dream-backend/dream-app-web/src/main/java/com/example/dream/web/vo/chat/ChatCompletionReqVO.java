package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天补全请求视图对象。
 *
 * <p>对接 RagFlow POST /chat/completions 请求体，字段命名兼容下划线风格。</p>
 *
 * @author dream
 */
@Data
public class ChatCompletionReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息列表（messages）
     */
    private List<ChatMessageVO> messages;

    /**
     * 对话 ID（dialog_id）
     */
    private Long dialogId;

    /**
     * 会话 ID（conversation_id）
     */
    private Long convId;

    /**
     * 指定聊天模型 ID（llm_id）
     */
    private String llmId;

    /**
     * 是否传入全量历史消息（pass_all_history_messages / pass_all_history）
     */
    private Boolean passAllHistoryMessages;

    /**
     * 生成参数（temperature/top_p/max_tokens 等）
     */
    private Map<String, Object> generationConfig;

    /**
     * 其余透传参数
     */
    private Map<String, Object> extraParams;
}