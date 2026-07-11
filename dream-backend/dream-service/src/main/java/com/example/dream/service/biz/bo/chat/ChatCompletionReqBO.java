package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天补全请求业务对象。
 *
 * <p>对应 RagFlow session_completion 的请求体 req，承载消息、会话/对话标识、
 * 模型与生成配置、流式与兼容开关等。</p>
 *
 * @author dream
 */
@Data
public class ChatCompletionReqBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息列表（对应 RagFlow messages）
     */
    private List<ChatMessageBO> messages;

    /**
     * 对话 ID（对应 RagFlow dialog_id）
     */
    private Long dialogId;

    /**
     * 会话 ID（对应 RagFlow session_id / conversation_id）
     */
    private Long convId;

    /**
     * 指定聊天模型 ID（对应 RagFlow llm_id）
     */
    private String llmId;

    /**
     * 是否传入全量历史消息（对应 RagFlow pass_all_history_messages / pass_all_history）
     */
    private Boolean passAllHistoryMessages = Boolean.FALSE;

    /**
     * 生成参数配置（对应 RagFlow pop_generation_config 提取的 temperature 等）
     */
    private Map<String, Object> generationConfig;

    /**
     * 其余透传参数（对应 RagFlow req 中剩余传给 async_chat 的字段）
     */
    private Map<String, Object> extraParams;
}