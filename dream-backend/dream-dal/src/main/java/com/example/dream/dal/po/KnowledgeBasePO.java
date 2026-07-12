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
     * 归属的用户ID
     */
    private String userId;

    /**
     * 包含的文档总数
     */
    private Integer docNum;

    /**
     * 总 Token 数量
     */
    private Integer tokenNum;

    /**
     * 切片/片段总数
     */
    private Integer chunkNum;

}
