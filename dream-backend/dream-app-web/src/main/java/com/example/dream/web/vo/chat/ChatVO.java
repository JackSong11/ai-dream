package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 聊天助手（Chat）视图对象，对接前端。id / datasetIds 转字符串避免精度丢失。
 *
 * @author dream
 */
@Data
public class ChatVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

    private String name;

    private String description;

    private String userId;

    private String llmId;

    private Map<String, Object> llmSetting;

    private Map<String, Object> promptConfig;

    private List<String> datasetIds;

    private List<String> kbNames;

    private String rerankId;

    private Integer topN;

    private Integer topK;

    private Double similarityThreshold;

    private Double vectorSimilarityWeight;

    private Date createdTime;

    private Date modifiedTime;
}