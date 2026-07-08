package com.example.dream.web.vo;

import lombok.Data;

/**
 * 文档视图对象对接前端返回。
 * <p>对应 RagFlow upload_document 返回的 map_doc_keys_with_run_status 结构。</p>
 */
@Data
public class DocumentVO {

    /**
     * 文档 ID
     */
    private Long id;

    /**
     * 文档名称
     */
    private String name;

    /**
     * 所属数据集 ID
     */
    private Long kdId;

    /**
     * 分块方式 / 解析器
     */
    private String chunkMethod;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * token 数量
     */
    private Integer tokenCount;

    /**
     * 文档类型
     */
    private String type;

    /**
     * 文件后缀
     */
    private String suffix;

    /**
     * 文件大小，字节
     */
    private Long size;

    /**
     * 对象存储位置
     */
    private String location;

    /**
     * 处理状态（"0"=未开始）
     */
    private int run;
}