package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 聊天助手创建 / 更新请求视图对象，对接 RagFlow POST/PUT /chats。
 *
 * @author dream
 */
@Data
public class ChatSaveReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String name;

    private String description;

    private String llmId;

    private Map<String, Object> llmSetting;

    private Map<String, Object> promptConfig;

    /**
     * 绑定知识库 ID 列表（字符串，前端传入）
     */
    private List<String> datasetIds;

    private String rerankId;

    private Integer topN;

    private Integer topK;

    private Double similarityThreshold;

    private Double vectorSimilarityWeight;
}