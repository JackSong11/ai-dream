package com.example.dream.service.biz.bo.kb;

import lombok.Data;

import java.util.Date;

/**
 * 文档列表项业务对象，对应 RagFlow document_api.map_doc_keys 的输出结构。
 * <p>用于知识库文档列表 GET /datasets/{id}/documents 返回。</p>
 */
@Data
public class DocItemBO {

    /**
     * 文档 ID（对应 RagFlow id）
     */
    private Long id;

    /**
     * 文档名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 所属知识库 ID（对应 RagFlow dataset_id / kb_id）
     */
    private Long kbId;

    /**
     * 分块方法 / 解析器（对应 RagFlow chunk_method / parser_id）
     */
    private String chunkMethod;

    /**
     * 解析器配置 JSON（对应 RagFlow parser_config）
     */
    private String parserConfig;

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
     * 分块数量（对应 RagFlow chunk_count）
     */
    private Integer chunkCount;

    /**
     * token 数量（对应 RagFlow token_count）
     */
    private Integer tokenCount;

    /**
     * 运行 / 处理状态（对应 RagFlow run，0=未开始）
     */
    private Integer run;

    /**
     * 解析进度，取值 0~1（对应 RagFlow document.progress）。
     */
    private java.math.BigDecimal progress;

    /**
     * 解析进度描述信息（对应 RagFlow document.progress_msg）。
     */
    private String progressMsg;

    /**
     * 文档状态（对应 RagFlow status）
     */
    private String status;

    /**
     * 错误信息（对应 RagFlow progress_msg / error）
     */
    private String errorMsg;

    /**
     * 创建时间（对应 RagFlow create_time）
     */
    private Date createdTime;

    /**
     * 更新时间（对应 RagFlow update_time）
     */
    private Date modifiedTime;
}