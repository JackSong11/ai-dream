package com.example.dream.web.vo.kb;

import lombok.Data;

/**
 * 更新知识库请求视图对象，对应 RagFlow PUT /datasets/{id} 请求体。
 * <p>字段为 null 表示不更新。</p>
 */
@Data
public class UpdateKbReqVO {

    /**
     * 新名称
     */
    private String name;

    /**
     * 新描述
     */
    private String description;

    /**
     * 新权限 me/team
     */
    private String permission;

    /**
     * 新分块方法 / 解析器
     */
    private String chunkMethod;

    /**
     * 新解析器配置 JSON
     */
    private String parserConfig;
}