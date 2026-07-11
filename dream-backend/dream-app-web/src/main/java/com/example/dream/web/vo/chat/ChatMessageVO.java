package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息视图对象（对应 RagFlow messages 单条消息）。
 *
 * @author dream
 */
@Data
public class ChatMessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色：user / assistant / system
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息 ID
     */
    private String id;

    /**
     * 关联文件列表
     */
    private List<Map<String, Object>> files;
}