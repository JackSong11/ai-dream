package com.example.dream.web.vo.kb;

import lombok.Data;

/**
 * 创建知识库请求视图对象，对应 RagFlow POST /datasets 请求体。
 */
@Data
public class CreateKbReqVO {

    /**
     * 知识库名称（必填）
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 可见性权限 me/team，默认 me
     */
    private String permission;

    /**
     * 分块方法 / 解析器，默认 naive
     */
    private String chunkMethod;

    /**
     * 解析器配置 JSON
     */
    private String parserConfig;
}