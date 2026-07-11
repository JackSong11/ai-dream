package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/**
 * 对话（Chat / Dialog）持久化对象。
 *
 * <p>对应 RagFlow db.Dialog 表，承载一个聊天助手的模型配置、知识库绑定、
 * prompt 配置、检索参数等。业务主键沿用 RagFlow 语义使用 UUID 字符串。</p>
 *
 * <p>复杂结构（llm_setting / prompt_config / kb_ids）以 JSON 字符串存储，
 * 在 Service 层转换为业务对象后使用，严禁跨层直接使用 PO。</p>
 *
 * @author dream
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("chat_dialog")
public class ChatDialogPO extends BasePO {

    /**
     * 对话名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 对话描述（对应 RagFlow description）
     */
    private String description;

    /**
     * 归属的用户ID
     */
    private String userId;

    /**
     * 聊天模型 ID（对应 RagFlow llm_id）
     */
    private String llmId;

    /**
     * LLM 生成参数配置（JSON 字符串，对应 RagFlow llm_setting）
     */
    private String llmSetting;

    /**
     * Prompt 配置（JSON 字符串，对应 RagFlow prompt_config）
     */
    private String promptConfig;

    /**
     * 绑定的知识库 ID 列表（JSON 数组字符串，对应 RagFlow kb_ids）
     */
    private String kbIds;

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