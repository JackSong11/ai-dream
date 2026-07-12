package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 聊天助手（Chat / Dialog）详情业务对象。
 *
 * <p>对应 RagFlow chat_api.py 中 _build_chat_response 输出结构：包含模型配置、
 * prompt 配置、绑定知识库、检索参数等，用于 chats 的创建 / 详情 / 列表返回。</p>
 *
 * @author dream
 */
@Data
public class DialogInfoBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 助手 ID（对应 RagFlow id）
     */
    private Long id;

    /**
     * 助手名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 助手描述（对应 RagFlow description）
     */
    private String description;

    /**
     * 归属用户 ID（对应 RagFlow tenant_id）
     */
    private String userId;

    /**
     * 聊天模型 ID（对应 RagFlow llm_id）
     */
    private String llmId;

    /**
     * LLM 生成参数（对应 RagFlow llm_setting）
     */
    private Map<String, Object> llmSetting;

    /**
     * Prompt 配置（对应 RagFlow prompt_config）
     */
    private Map<String, Object> promptConfig;

    /**
     * 绑定的知识库 ID 列表（对应 RagFlow dataset_ids / kb_ids）
     */
    private List<Long> datasetIds;

    /**
     * 绑定的知识库名称列表（对应 RagFlow kb_names）
     */
    private List<String> kbNames;

    /**
     * rerank 模型 ID（对应 RagFlow rerank_id）
     */
    private String rerankId;

    /**
     * 召回条数（对应 RagFlow top_n）
     */
    private Integer topN;

    /**
     * 向量召回条数（对应 RagFlow top_k）
     */
    private Integer topK;

    /**
     * 相似度阈值（对应 RagFlow similarity_threshold）
     */
    private Double similarityThreshold;

    /**
     * 向量相似度权重（对应 RagFlow vector_similarity_weight）
     */
    private Double vectorSimilarityWeight;

    /**
     * 创建时间（对应 RagFlow create_time）
     */
    private Date createdTime;

    /**
     * 更新时间（对应 RagFlow update_time）
     */
    private Date modifiedTime;
}