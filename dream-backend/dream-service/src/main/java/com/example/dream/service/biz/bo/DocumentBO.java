package com.example.dream.service.biz.bo;

import lombok.Data;

/**
 * 文档业务对象，对应 RagFlow map_doc_keys_with_run_status 的输出结构。
 * <p>用于 local 上传主流程返回上传成功的文档信息。</p>
 */
@Data
public class DocumentBO {

    /**
     * 文档 ID（对应 RagFlow id）
     */
    private String id;

    /**
     * 文档名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 所属数据集 ID（对应 RagFlow dataset_id / kb_id）
     */
    private String datasetId;

    /**
     * 分块方式 / 解析器（对应 RagFlow chunk_method / parser_id）
     */
    private String chunkMethod;

    /**
     * 分块数量（对应 RagFlow chunk_count）
     */
    private Integer chunkCount;

    /**
     * token 数量（对应 RagFlow token_count）
     */
    private Integer tokenCount;

    /**
     * 文档类型（对应 RagFlow type）
     */
    private String type;

    /**
     * 文件后缀（对应 RagFlow suffix）
     */
    private String suffix;

    /**
     * 文件大小，字节（对应 RagFlow size）
     */
    private Long size;

    /**
     * 对象存储位置（对应 RagFlow location）
     */
    private String location;

    /**
     * 处理状态（对应 RagFlow run，"0"=未开始）
     */
    private String run;
}