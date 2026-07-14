package com.example.dream.web.vo.ai;

import lombok.Data;

/**
 * 多模型对话请求 VO。
 *
 * @author dream
 */
@Data
public class ChatReqVO {

    /**
     * 模型标识。为空时使用默认模型。
     */
    private String modelKey;

    /**
     * 系统提示词，可为空。
     */
    private String systemPrompt;

    /**
     * 用户输入内容。
     */
    private String message;
}