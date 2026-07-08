package com.example.dream.web.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 文档解析/运行请求 VO。
 *
 * <p>对应 RagFlow document_api.py 中 POST /documents/ingest 的请求体：
 * { "doc_ids": [...], "run": "1", "delete": false, "apply_kb": false }。</p>
 *
 * @author dream
 */
@Data
public class IngestReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待处理的文档 ID 列表（对应 RagFlow req["doc_ids"]）
     */
    private List<Long> docIds;

    /**
     * 目标运行状态（对应 RagFlow req["run"]，如 "1"=RUNNING、"2"=CANCEL）
     */
    private int run;

    /**
     * 是否删除已有解析结果（对应 RagFlow req["delete"]）
     */
    private Boolean delete;

    /**
     * 是否应用知识库解析配置（对应 RagFlow req["apply_kb"]）
     */
    private Boolean applyKb;
}