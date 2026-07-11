package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 聊天补全（非流式）响应视图对象。
 *
 * <p>对接前端接口，字段命名兼容 RagFlow 下划线风格。由 ChatAnswerBO 转换而来。</p>
 *
 * @author dream
 */
@Data
public class ChatAnswerVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生成的答案文本（answer）
     */
    private String answer;

    /**
     * 引用信息（reference）
     */
    private Map<String, Object> reference;

    /**
     * 关联的用户消息 ID（id）
     */
    private String id;

    /**
     * 会话 ID（conversation_id）
     */
    private Long convId;

    /**
     * 对话 ID（dialog_id）
     */
    private Long dialogId;

    /**
     * 音频 base64（audio_binary，可选）
     */
    private String audioBinary;
}