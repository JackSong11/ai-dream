package com.example.dream.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档解析任务消息体。
 *
 * <p>由生产端（dream-service ingest）拆分文档后投递到 Redis Stream，
 * 消费端（dream-processor）从队列取出后据此执行分块→向量化→写 ES 全流程。
 * 对应 RagFlow task_executor.collect() 返回的 task 字典。</p>
 *
 * @author dream
 */
@Data
public class DocTaskMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务 ID（对应 RagFlow task["id"]，即 kb_task 主键）
     */
    private Long taskId;

    /**
     * 文档 ID（对应 RagFlow task["doc_id"]）
     */
    private Long docId;

    /**
     * 知识库/数据集 ID（对应 RagFlow task["kb_id"]）
     */
    private Long kbId;

    /**
     * 用户 ID，用于确定 ES 索引名（对应 RagFlow task["user_id"]）
     */
    private String userId;

    /**
     * 解析器 ID（对应 RagFlow task["parser_id"]）
     */
    private String parserId;

    /**
     * 解析器配置 JSON 字符串（对应 RagFlow task["parser_config"]）
     */
    private String parserConfig;

    /**
     * 文档名称（对应 RagFlow task["name"]）
     */
    private String name;

    /**
     * 对象存储 key（对应 RagFlow task["location"]，用于从 MinIO 拉取文件二进制）
     */
    private String location;

    /**
     * 起始页码（对应 RagFlow task["from_page"]）
     */
    private Integer fromPage;

    /**
     * 结束页码，-1 表示到末尾（对应 RagFlow task["to_page"]）
     */
    private Integer toPage;

    /**
     * 文件大小，字节（对应 RagFlow task["size"]，用于超限校验）
     */
    private Long size;
}