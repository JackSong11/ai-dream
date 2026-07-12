package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 会话（Session）视图对象，对接前端。id / chatId 转字符串避免精度丢失。
 *
 * @author dream
 */
@Data
public class SessionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

    private String chatId;

    private String userId;

    private String name;

    private List<Map<String, Object>> messages;

    private List<Object> reference;

    private Date createdTime;

    private Date modifiedTime;
}