package com.example.dream.service.biz.bo.kb;

import lombok.Data;

/**
 * 更新知识库请求业务对象，对应 RagFlow UpdateDatasetReq。
 * <p>字段为 null 表示不更新（对应 Python exclude_unset）。</p>
 */
@Data
public class UpdateKbBO {

    /**
     * 新名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 新描述（对应 RagFlow description）
     */
    private String description;

    /**
     * 新权限 me/team（对应 RagFlow permission）
     */
    private String permission;

    /**
     * 新分块方法 / 解析器（对应 RagFlow chunk_method / parser_id）
     */
    private String chunkMethod;

    /**
     * 新解析器配置 JSON（对应 RagFlow parser_config）
     */
    private String parserConfig;
}