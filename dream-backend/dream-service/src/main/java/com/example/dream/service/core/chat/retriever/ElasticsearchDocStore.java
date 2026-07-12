package com.example.dream.service.core.chat.retriever;

import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.es.HybridSearchResult;
import com.example.dream.dal.po.KbDocumentPO;
import com.example.dream.service.core.KbDocumentCoreService;
import com.example.dream.service.core.chat.retriever.nlp.MatchTextExpr;
import com.example.dream.integration.service.es.HybridSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * {@inheritDoc}
     *
     * <p>把编排层的 {@link DocStoreSearchRequest}（含 {@link MatchTextExpr}）翻译为 integration 层
     * 的 {@link HybridSearchRequest}，委托 {@link ElasticsearchService#hybridSearch} 执行一次混合召回，
     * 并把命中 chunk 的 {@code doc_id}/{@code kb_id} 归一化为 {@link Long}（ES 中为 keyword 字符串），
     * 以对齐 {@code Dealer._prune_deleted_chunks} 与 {@code existingDocIds(Set<Long>)} 的类型约定。</p>
     */
    @Override
    public HybridSearchResult search(DocStoreSearchRequest req) {
        HybridSearchRequest esReq = new HybridSearchRequest();
        esReq.setIndexNames(req.getIdxNames());
        esReq.setKbIds(req.getKbIds());
        esReq.setDocIds(req.getDocIds());
        esReq.setAvailableInt(req.getAvailableInt());
        esReq.setSourceFields(req.getSourceFields());
        esReq.setQueryVector(req.getQueryVector());
        esReq.setTopk(req.getTopk());
        esReq.setSimilarity(req.getSimilarity());
        esReq.setOffset(req.getOffset());
        esReq.setLimit(req.getLimit());

        MatchTextExpr matchText = req.getMatchText();
        if (matchText != null) {
            esReq.setQueryString(matchText.getMatchingText());
            esReq.setQueryFields(matchText.getFields());
            esReq.setMinimumShouldMatch(matchText.getMinimumShouldMatch());
        }

        HybridSearchResult result = elasticsearchService.hybridSearch(esReq);
        normalizeLongFields(result);
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接委托 {@link ElasticsearchService#knnScore}，对首次召回的 chunk id 集合做二次纯 KNN 打分。</p>
     */
    @Override
    public Map<String, Double> knnScores(List<String> idxNames,
                                         List<Long> kbIds,
                                         List<String> chunkIds,
                                         List<Float> queryVector) {
        return elasticsearchService.knnScore(idxNames, kbIds, chunkIds, queryVector);
    }

    /**
     * 将命中 chunk 字段中的 doc_id / kb_id 归一化为 Long（ES 存 keyword 字符串）。
     */
    private void normalizeLongFields(HybridSearchResult result) {
        if (result == null || CollectionUtils.isEmpty(result.getFields())) {
            return;
        }
        for (Map<String, Object> field : result.getFields().values()) {
            if (field == null) {
                continue;
            }
            field.computeIfPresent("doc_id", (k, v) -> toLong(v));
            field.computeIfPresent("kb_id", (k, v) -> toLong(v));
        }
    }

    private Object toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Long) {
            return v;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return v;
        }
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
    public Set<Long> existingDocIds(Collection<Long> docIds) {
        if (CollectionUtils.isEmpty(docIds)) {
            return new HashSet<>();
        }
        // 去重（对应 Python list(dict.fromkeys(doc_ids))）
        Set<Long> ids = new LinkedHashSet<>(docIds);
        List<KbDocumentPO> kbDocumentPOS = kbDocumentCoreService.listByIds(ids);
        if (!CollectionUtils.isEmpty(kbDocumentPOS)) {
            return kbDocumentPOS.stream()
                    .filter(po -> po != null && po.getId() != null)
                    .map(KbDocumentPO::getId)
                    .collect(Collectors.toSet());
        }
        return new HashSet<>();
    }
}