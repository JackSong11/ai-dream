package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档表持久化对象
 * kb_document
 *
 * <p>字段对齐 RagFlow document 表在 local 上传主流程中使用到的核心字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true) // 显式声明：包含父类属性
@TableName("kb_document")
public class KbDocumentPO extends BasePO {

    /**
     * 所属知识库/数据集 ID（对应 RagFlow kb_id）
     */
    private Long kbId;

    /**
     * 解析器 ID（对应 RagFlow parser_id，如 naive/picture/audio/presentation/email）
     */
    private String parserId;

    /**
     * 解析器配置（JSON 字符串，对应 RagFlow parser_config）
     */
    private String parserConfig;

    /**
     * 文档类型（对应 RagFlow FileType：doc/visual/aural/virtual/other 等）
     */
    private String type;

    /**
     * 文档名称（对应 RagFlow name）
     */
    private String fileName;

    /**
     * 文件后缀（对应 RagFlow suffix，不含 "."）
     */
    private String suffix;

    /**
     * 对象存储中的位置/对象名（对应 RagFlow location）
     */
    private String objectKey;

    /**
     * 文件大小，字节（对应 RagFlow size）
     */
    private Long size;

    /**
     * 运行/处理状态（对应 RagFlow run，"0"=未开始 UNSTART）
     */
    private int run;

    /**
     * 文档状态
     */
    private String status;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * token 数量（对应 RagFlow token_count）
     */
    private Integer tokenCount;

    /**
     * 错误信息
     */
    private String errorMsg;
}