package com.example.dream.service.biz.bo.kb;

import lombok.Data;

/**
 * 创建知识库请求业务对象，对应 RagFlow CreateDatasetReq。
 */
@Data
public class CreateKbBO {

    /**
     * 知识库名称（必填，对应 RagFlow name）
     */
    private String name;

    /**
     * 知识库描述（对应 RagFlow description）
     */
    private String description;

    /**
     * 可见性权限 me/team（对应 RagFlow permission），默认 me
     */
    private String permission;

    /**
     * 分块方法 / 解析器（对应 RagFlow chunk_method / parser_id），默认 naive
     */
    private String chunkMethod;

    /**
     * 解析器配置 JSON（对应 RagFlow parser_config）
     */
    private String parserConfig;
}