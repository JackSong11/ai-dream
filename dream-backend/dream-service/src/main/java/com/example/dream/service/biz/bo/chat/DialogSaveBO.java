package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天助手（Chat / Dialog）创建 / 更新入参业务对象。
 *
 * <p>对应 RagFlow chat_api.py create / update_chat 的请求体，字段为 null 表示不设置 / 不更新。</p>
 *
 * @author dream
 */
@Data
public class DialogSaveBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 助手名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 助手描述（对应 RagFlow description）
     */
    private String description;

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
     * 绑定的知识库 ID 列表（对应 RagFlow dataset_ids）
     */
    private List<Long> datasetIds;

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
}