package com.example.dream.service.biz.bo;

import lombok.Data;

import java.util.List;

/**
 * 文档解析/运行业务对象。
 *
 * <p>对应 RagFlow document_api.py 中 ingest 接口的请求体，
 * 由 web 层 IngestReqVO 转换而来，供 service 层编排使用。</p>
 *
 * @author dream
 */
@Data
public class IngestBO {

    /**
     * 待处理的文档 ID 列表（对应 RagFlow req["doc_ids"]）
     */
    private List<Long> docIds;

    /**
     * 目标运行状态（对应 RagFlow req["run"]）
     */
    private int run;

    /**
     * 是否删除已有解析结果（对应 RagFlow req["delete"]）
     */
    private boolean delete;

    /**
     * 是否应用知识库解析配置（对应 RagFlow req["apply_kb"]）
     */
    private boolean applyKb;
}