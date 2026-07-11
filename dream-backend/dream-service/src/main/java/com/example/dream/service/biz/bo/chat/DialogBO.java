package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话运行时业务对象。
 *
 * <p>对应 RagFlow session_completion 中使用的 dia 对象（来自 DialogService 或
 * _build_default_completion_dialog 构造的 SimpleNamespace），承载本轮对话调用
 * async_chat 所需的模型配置、prompt 配置、知识库与检索参数。</p>
 *
 * @author dream
 */
@Data
public class DialogBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 聊天模型 ID（对应 RagFlow dia.llm_id）
     */
    private String llmId;

    /**
     * 归属的用户ID
     */
    private String userId;

    /**
     * LLM 生成参数（对应 RagFlow dia.llm_setting）
     */
    private Map<String, Object> llmSetting = new HashMap<>();

    /**
     * prompt 配置（对应 RagFlow dia.prompt_config）
     * 里面一定有个key存系统提示词：system
     */
    private Map<String, Object> promptConfig = new HashMap<>();

    /**
     * 知识库 ID 列表（对应 RagFlow dia.kb_ids）
     */
    private List<Long> kbIds;

    /**
     * 召回条数（对应 RagFlow dia.top_n）
     */
    private Integer topN = 6;

    /**
     * 向量召回条数（对应 RagFlow dia.top_k）
     */
    private Integer topK = 1024;

    /**
     * rerank 模型 ID（对应 RagFlow dia.rerank_id）
     */
    private String rerankId = "";

    /**
     * 相似度阈值（对应 RagFlow dia.similarity_threshold）
     */
    private Double similarityThreshold = 0.1;

    /**
     * 向量相似度权重（对应 RagFlow dia.vector_similarity_weight）
     */
    private Double vectorSimilarityWeight = 0.3;

    /**
     * 元数据过滤配置（对应 RagFlow dia.meta_data_filter）
     */
    private Map<String, Object> metaDataFilter;

    /**
     * 构造默认的直连聊天对话配置。
     *
     * <p>对应 RagFlow _build_default_completion_dialog：无知识库、空 prompt_config。</p>
     *
     * @param userId 归属用户 ID
     * @return 默认对话配置
     */
    public static DialogBO buildDefaultCompletionDialog(String userId) {
        DialogBO dia = new DialogBO();
        dia.setUserId(userId);
        dia.setLlmId("");
        dia.setLlmSetting(new HashMap<>());
        dia.setPromptConfig(new HashMap<>());
        dia.setKbIds(new java.util.ArrayList<>());
        dia.setTopN(6);
        dia.setTopK(1024);
        dia.setRerankId("");
        dia.setSimilarityThreshold(0.1);
        dia.setVectorSimilarityWeight(0.3);
        dia.setMetaDataFilter(null);
        return dia;
    }
}