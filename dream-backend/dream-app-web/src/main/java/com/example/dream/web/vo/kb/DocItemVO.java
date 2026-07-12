package com.example.dream.web.vo.kb;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 文档列表项视图对象，对接前端。
 * <p>对应 RagFlow document_api.map_doc_keys 输出结构。</p>
 */
@Data
public class DocItemVO {

    /**
     * 文档 ID
     */
    private String id;

    /**
     * 文档名称
     */
    private String name;

    /**
     * 所属知识库 ID
     */
    private String kbId;

    /**
     * 分块方法 / 解析器
     */
    private String chunkMethod;

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
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * token 数量
     */
    private Integer tokenCount;

    /**
     * 运行 / 处理状态（0=未开始）
     */
    private Integer run;

    /**
     * 解析进度，取值 0~1（前端据此展示进度条）
     */
    private java.math.BigDecimal progress;

    /**
     * 解析进度描述信息
     */
    private String progressMsg;

    /**
     * 文档状态
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMsg;

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