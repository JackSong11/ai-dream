package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 聊天助手列表视图对象，对应 RagFlow list_chats 返回 {chats, total}。
 *
 * @author dream
 */
@Data
public class ChatListVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<ChatVO> chats;

    private long total;
}