package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 会话（Session / Conversation）业务对象。
 *
 * <p>对应 RagFlow chat_api.py 中 _build_session_response 输出结构：dialog_id 映射为
 * chat_id，message 映射为 messages。用于 sessions 的创建 / 详情 / 列表返回。</p>
 *
 * @author dream
 */
@Data
public class SessionBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 会话 ID（对应 RagFlow id）
     */
    private Long id;

    /**
     * 所属助手 ID（对应 RagFlow chat_id / dialog_id）
     */
    private Long chatId;

    /**
     * 归属用户 ID（对应 RagFlow user_id）
     */
    private String userId;

    /**
     * 会话名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 消息列表（对应 RagFlow messages / message）
     */
    private List<Map<String, Object>> messages;

    /**
     * 引用列表（对应 RagFlow reference）
     */
    private List<Object> reference;

    /**
     * 创建时间（对应 RagFlow create_time）
     */
    private Date createdTime;

    /**
     * 更新时间（对应 RagFlow update_time）
     */
    private Date modifiedTime;
}