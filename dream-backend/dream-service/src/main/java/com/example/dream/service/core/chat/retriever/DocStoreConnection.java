package com.example.dream.service.core.chat.retriever;

import com.example.dream.integration.service.es.HybridSearchResult;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 存储抽象层契约（对应 RagFlow common/doc_store/doc_store_base.py 中的抽象基类
 * {@code DocStoreConnection}）。
 *
 * <p>这是 RagFlow 分层设计中「变的部分」的抽象边界：检索编排层 {@link Dealer} 只依赖本抽象，
 * 不感知底层是 Elasticsearch / Infinity / OceanBase。各引擎各自实现原始 I/O：
 * <ul>
 *   <li>ES 实现：{@link ElasticsearchDocStore}（委托 integration 层的 ES 客户端封装）；</li>
 *   <li>后续可扩展 Infinity / OceanBase 等实现，无需改动编排层。</li>
 * </ul>
 * </p>
 *
 * <p>按「用到什么迁移什么」的原则，当前仅抽象编排层实际调用的混合检索能力
 * （对应 RagFlow {@code DocStoreConnection.search} 配合 {@code Dealer.search}/{@code _knn_scores}
 * 的一次召回 + 二次纯 KNN 打分）。insert / update / delete / get / sql 等其它 CRUD 能力
 * 后续用到时再补充到本接口。</p>
 *
 * @author dream
 */
public interface DocStoreConnection {

    /**
     * 混合检索：全文 match + 向量 KNN（dense_vector cosine）加权融合的一次召回，
     * 并对候选做二次纯 KNN 打分，返回原始命中供编排层做融合 / 过滤 / 分页 / 聚合。
     *
     * <p>对应 RagFlow {@code Dealer.search}（第一次带 fusion 的召回，含空结果降级重试）
     * 与 {@code Dealer._knn_scores}（对候选 id 的二次纯 KNN 打分），返回结构对应
     * {@code Dealer.SearchResult}。</p>
     *
     * @param indexNames  待检索索引名列表（对应 index_name(tenant_id)）
     * @param kbIds       知识库 id 过滤（对应 filters.kb_id）
     * @param docIds      文档 id 过滤，可为空（对应 filters.doc_id）
     * @param question    用户问题文本（用于全文 match）
     * @param queryVector query 的嵌入向量（用于 KNN，dense_vector cosine）
     * @param size        召回窗口大小（对应 req.size / RERANK_LIMIT）
     * @param topk        向量候选池上限（对应 req.topk）
     * @param similarity  向量相似度阈值（对应 req.similarity）
     * @return 混合检索原始命中（对应 SearchResult）
     */
    HybridSearchResult hybridSearch(List<String> indexNames,
                                    List<Long> kbIds,
                                    List<Long> docIds,
                                    String question,
                                    List<Float> queryVector,
                                    int size,
                                    int topk,
                                    double similarity);

    /**
     * 从给定的 doc_id 集合中，筛出「文档仍然存在」的那部分。
     *
     * <p>对应 RagFlow {@code Dealer._existing_doc_ids}（内部委托
     * {@code DocumentService.get_by_ids}）：检索命中的 chunk 可能来自已被删除、
     * 但向量记录未彻底清理的残留文档，编排层需据此在 rerank 前剔除这些孤儿 chunk
     * （见 {@code Dealer._prune_deleted_chunks}）。</p>
     *
     * @param docIds 待校验的文档 id 集合（对应 chunk 的 doc_id，可能含重复）
     * @return 其中仍然存在的文档 id 集合
     */
    Set<Long> existingDocIds(Collection<Long> docIds);
}