package com.example.dream.service.biz.bo.kb;

import lombok.Data;

import java.util.Date;

/**
 * 知识库业务对象，对应 RagFlow dataset 的 remap_dictionary_keys 输出结构。
 * <p>用于知识库创建 / 详情 / 列表返回。</p>
 */
@Data
public class KnowledgeBaseBO {

    /**
     * 知识库 ID（对应 RagFlow id）
     */
    private Long id;

    /**
     * 知识库名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 知识库描述（对应 RagFlow description）
     */
    private String description;

    /**
     * 归属用户 ID（对应 RagFlow tenant_id）
     */
    private String userId;

    /**
     * 可见性权限 me/team（对应 RagFlow permission）
     */
    private String permission;

    /**
     * 分块方法 / 解析器（对应 RagFlow chunk_method / parser_id）
     */
    private String chunkMethod;

    /**
     * 解析器配置 JSON（对应 RagFlow parser_config）
     */
    private String parserConfig;

    /**
     * 文档总数（对应 RagFlow document_count / doc_num）
     */
    private Integer docNum;

    /**
     * token 总数（对应 RagFlow token_num）
     */
    private Integer tokenNum;

    /**
     * 分块总数（对应 RagFlow chunk_num）
     */
    private Integer chunkNum;

    /**
     * 创建时间（对应 RagFlow create_time）
     */
    private Date createdTime;

    /**
     * 更新时间（对应 RagFlow update_time）
     */
    private Date modifiedTime;
}