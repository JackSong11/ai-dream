package com.example.dream.service.core.chat.retriever;

import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.es.HybridSearchResult;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.service.core.KbDocumentCoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 存储抽象层的 Elasticsearch 实现（对应 RagFlow rag/utils/es_conn.py 中的 {@code ESConnection}）。
 *
 * <p>这是分层设计中「变的部分」的 ES 落地：只负责把编排层 {@link Dealer} 需要的存储 I/O
 * 委托给 integration 层的 {@link ElasticsearchService}（官方 ES Java Client 封装）。
 * 唯有到这一层，命名才与具体引擎（Elasticsearch）绑定 —— 编排层与查询构建层均保持引擎无关。</p>
 *
 * <p>后续若接入 Infinity / OceanBase，只需新增对应的 {@link DocStoreConnection} 实现（如
 * {@code InfinityDocStore} / {@code OceanBaseDocStore}），编排层 {@link Dealer} 无需任何改动。</p>
 *
 * @author dream
 */
@Component
@RequiredArgsConstructor
public class ElasticsearchDocStore implements DocStoreConnection {

    /**
     * integration 层的 ES 客户端封装（真正的存储 I/O，对应 RagFlow ESConnection 内部的 self.es）。
     */
    private final ElasticsearchService elasticsearchService;

    /**
     * 文档持久化 Service（对应 RagFlow DocumentService，用于校验 chunk 所属文档是否仍存在）。
     */
    private final KbDocumentCoreService kbDocumentCoreService;

    @Override
    public HybridSearchResult hybridSearch(List<String> indexNames,
                                           List<Long> kbIds,
                                           List<Long> docIds,
                                           String question,
                                           List<Float> queryVector,
                                           int size,
                                           int topk,
                                           double similarity) {
        return elasticsearchService.hybridSearch(indexNames, kbIds, docIds, question,
                null, queryVector, size, topk, similarity);
    }

    /**
     * {@inheritDoc}
     *
     * <p>对应 RagFlow {@code Dealer._existing_doc_ids}：先去重，再委托
     * {@link KbDocumentCoreService}（对应 {@code DocumentService.get_by_ids}）
     * 查库，返回仍然存在的文档 id 集合。ES 中 chunk 的 doc_id 存的是文档主键（Long），
     * 这里将其解析为主键做批量 in 查询，再以字符串形式返回以对齐 chunk.doc_id 的比较。</p>
     */
    @Override
    public Set<String> existingDocIds(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return new HashSet<>();
        }
        // 去重（对应 Python list(dict.fromkeys(doc_ids))）
        Set<String> unique = new LinkedHashSet<>(docIds);
        Set<Long> ids = new LinkedHashSet<>();
        for (String d : unique) {
            try {
                ids.add(Long.parseLong(d.trim()));
            } catch (NumberFormatException ignore) {
                // 非法 doc_id 直接跳过，视为不存在
            }
        }
        if (ids.isEmpty()) {
            return new HashSet<>();
        }
        Set<String> existing = new HashSet<>();
        for (KbDocumentPO po : kbDocumentCoreService.listByIds(ids)) {
            if (po != null && po.getId() != null) {
                existing.add(String.valueOf(po.getId()));
            }
        }
        return existing;
    }
}