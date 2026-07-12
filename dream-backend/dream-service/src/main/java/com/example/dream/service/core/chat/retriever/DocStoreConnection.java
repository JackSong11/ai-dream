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
     * 一次混合检索（全文 match + 可选向量 KNN，带 weighted_sum 融合）。
     *
     * <p>对应 RagFlow {@code DocStoreConnection.search} 配合 {@code Dealer.search}：
     * 全文表达式由 {@link com.example.dream.service.core.chat.retriever.nlp.MatchTextExpr} 承载，
     * 向量部分由 {@code queryVector} 触发（为空则退化为纯全文召回）。返回按引擎排序的命中
     * chunk（id -> 字段 Map，含 {@code _score}）与 total，供编排层 {@link Dealer} 做后续
     * rerank、阈值过滤、分页与聚合。</p>
     *
     * @param req 检索请求参数（索引名、kb/doc 过滤、全文表达式、查询向量、分页等）
     * @return 命中结果（total / ids / fields）
     */
    HybridSearchResult search(DocStoreSearchRequest req);

    /**
     * 二次纯 KNN 打分（对应 RagFlow {@code Dealer._knn_scores}）。
     *
     * <p>ES 路径下，首次混合召回不回传 chunk 向量；此处用 query 向量对已命中的
     * chunk id 集合做一次纯 KNN 查询，取回干净的余弦相似度分（chunk id -> score），
     * 供 {@code Dealer.rerank_with_knn} 与 term 相似度融合。</p>
     *
     * @param idxNames    索引名列表
     * @param kbIds       知识库 id 过滤
     * @param chunkIds    需要打分的 chunk id 集合（首次召回结果）
     * @param queryVector query 的嵌入向量
     * @return chunk id -> 余弦相似度分
     */
    java.util.Map<String, Double> knnScores(List<String> idxNames,
                                             List<Long> kbIds,
                                             List<String> chunkIds,
                                             List<Float> queryVector);

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