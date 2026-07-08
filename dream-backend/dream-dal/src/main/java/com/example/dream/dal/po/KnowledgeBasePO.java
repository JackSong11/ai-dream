package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识库持久化对象
 * knowledge_base
 */
@Data
@EqualsAndHashCode(callSuper = true) // 显式声明：包含父类属性
@TableName("knowledge_base")
public class KnowledgeBasePO extends BasePO {

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 包含的文档总数
     */
    private Integer doc_num;

    /**
     * 总 Token 数量
     */
    private Integer token_num;

    /**
     * 切片/片段总数
     */
    private Integer chunk_num;

    /**
     * 解析器 ID（对应 RagFlow parser_id）
     */
    private String parserId;

    /**
     * 解析器配置（JSON 字符串，对应 RagFlow parser_config）
     */
    private String parserConfig;

    /**
     * 处理流水线 ID（对应 RagFlow pipeline_id）
     */
    private String pipelineId;

}
