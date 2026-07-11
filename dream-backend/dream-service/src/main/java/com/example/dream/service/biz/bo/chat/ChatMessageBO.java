package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息业务对象。
 *
 * <p>对应 RagFlow messages 中的单条消息 {role, content, id, files, ...}。</p>
 *
 * @author dream
 */
@Data
public class ChatMessageBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 角色：user / assistant / system（对应 RagFlow role）
     */
    private String role;

    /**
     * 消息内容（对应 RagFlow content）
     */
    private String content;

    /**
     * 消息 ID（对应 RagFlow id，最后一条 user 消息若缺失会自动生成 UUID）
     */
    private String id;

    /**
     * 关联文件列表（对应 RagFlow files，可选）
     */
    private List<Map<String, Object>> files;

    /**
     * 关联文档 ID 列表（对应 RagFlow messages[-1]["doc_ids"]，可选）
     */
    private List<String> docIds;

    /**
     * 消息创建时间戳（秒，对应 RagFlow message created_at，可选）
     */
    private Double createdAt;


    public static ChatMessageBO of(String role, String content) {
        ChatMessageBO bo = new ChatMessageBO();
        bo.setRole(role);
        bo.setContent(content);
        return bo;
    }
}