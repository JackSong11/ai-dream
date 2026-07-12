package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话创建 / 重命名请求视图对象，对接 RagFlow POST/PATCH /sessions。
 *
 * @author dream
 */
@Data
public class SessionSaveReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;
}