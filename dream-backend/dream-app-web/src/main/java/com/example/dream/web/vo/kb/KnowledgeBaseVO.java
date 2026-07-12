package com.example.dream.web.vo.kb;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 知识库视图对象，对接前端。
 * <p>对应 RagFlow dataset 返回结构。id 序列化为字符串避免前端 Long 精度丢失。</p>
 */
@Data
public class KnowledgeBaseVO {

    /**
     * 知识库 ID
     */
    private String id;

    /**
     * 知识库名称
     */
    private String name;

    /**
     * 知识库描述
     */
    private String description;

    /**
     * 归属用户 ID
     */
    private String userId;

    /**
     * 可见性权限 me/team
     */
    private String permission;

    /**
     * 分块方法 / 解析器
     */
    private String chunkMethod;

    /**
     * 文档总数
     */
    private Integer docNum;

    /**
     * token 总数
     */
    private Integer tokenNum;

    /**
     * 分块总数
     */
    private Integer chunkNum;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createdTime;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date modifiedTime;
}