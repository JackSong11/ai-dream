package com.example.dream.integration.service.es;

import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 通用操作接口。
 * <p>对官方 Java API Client 进行封装，屏蔽底层细节，供上层业务调用，
 * 覆盖索引管理、文档 CRUD、批量、搜索等常用操作。</p>
 *
 * @author dream
 */
public interface ElasticsearchService {

    // ==================== 索引操作 ====================

    /**
     * 判断索引是否存在。
     *
     * @param index 索引名
     * @return 是否存在
     */
    boolean indexExists(String index);

    /**
     * 创建索引（使用默认配置）。
     *
     * @param index 索引名
     * @return 是否创建成功
     */
    boolean createIndex(String index);

    /**
     * 删除索引。
     *
     * @param index 索引名
     * @return 是否删除成功
     */
    boolean deleteIndex(String index);

    // ==================== 文档操作 ====================

    /**
     * 创建用于存储文档分块的知识库索引（若已存在则跳过）。
     * <p>对应 RagFlow docStoreConn.create_idx：为向量字段 q_{vectorSize}_vec
     * 建立 dense_vector 映射（启用 cosine 相似度检索）。</p>
     *
     * @param index      索引名
     * @param vectorSize 向量维度
     * @return 是否创建成功（已存在也返回 true）
     */
    boolean createChunkIndexIfAbsent(String index, int vectorSize);

    /**
     * 新增或全量覆盖文档。
     *
     * @param index    索引名
     * @param id       文档 ID
     * @param document 文档对象
     * @param <T>      文档类型
     * @return 文档 ID
     */
    <T> String saveDocument(String index, String id, T document);

    /**
     * 根据 ID 查询文档。
     *
     * @param index 索引名
     * @param id    文档 ID
     * @param clazz 文档类型
     * @param <T>   文档类型
     * @return 文档对象，不存在时返回 null
     */
    <T> T getDocument(String index, String id, Class<T> clazz);

    /**
     * 局部更新文档。
     *
     * @param index      索引名
     * @param id         文档 ID
     * @param partialDoc 局部字段
     * @param <T>        文档类型
     * @return 是否更新成功
     */
    <T> boolean updateDocument(String index, String id, T partialDoc);

    /**
     * 根据 ID 删除文档。
     *
     * @param index 索引名
     * @param id    文档 ID
     * @return 是否删除成功
     */
    boolean deleteDocument(String index, String id);

    /**
     * 根据字段精确匹配（term）批量删除文档。
     * <p>对应 RagFlow docStoreConn.delete({field: value}, index, kb_id)。</p>
     *
     * @param index 索引名
     * @param field 字段名
     * @param value 字段值
     * @return 删除的文档数量
     */
    long deleteByTerm(String index, String field, String value);

    /**
     * 批量新增文档。
     *
     * @param index       索引名
     * @param idDocuments 文档 ID 与文档对象的映射
     * @param <T>         文档类型
     * @return 是否全部成功
     */
    <T> boolean bulkSave(String index, Map<String, T> idDocuments);

    // ==================== 搜索操作 ====================

    /**
     * 匹配全部文档（match_all）。
     *
     * @param index 索引名
     * @param clazz 文档类型
     * @param <T>   文档类型
     * @return 文档列表
     */
    <T> List<T> matchAll(String index, Class<T> clazz);

    /**
     * 单字段精确匹配查询（term）。
     *
     * @param index 索引名
     * @param field 字段名
     * @param value 字段值
     * @param clazz 文档类型
     * @param <T>   文档类型
     * @return 匹配的文档列表
     */
    <T> List<T> searchByTerm(String index, String field, String value, Class<T> clazz);

    /**
     * 单字段全文检索（match）。
     *
     * @param index 索引名
     * @param field 字段名
     * @param text  检索文本
     * @param clazz 文档类型
     * @param <T>   文档类型
     * @return 匹配的文档列表
     */
    <T> List<T> searchByMatch(String index, String field, String text, Class<T> clazz);

    // ==================== 混合检索（全文 + 向量 KNN）====================

    /**
     * 混合检索：全文 match + 向量 KNN（dense_vector cosine）加权融合的一次召回，
     * 并对候选做二次纯 KNN 打分，返回原始命中供上层按 RagFlow 逻辑做融合/过滤/分页/聚合。
     *
     * <p>严格对应 RagFlow rag/nlp/search.py 的 {@code Dealer.search}（第一次带 fusion 的召回）
     * 与 {@code Dealer._knn_scores}（对候选 id 的二次纯 KNN 打分），返回结果对应
     * {@code Dealer.SearchResult}。</p>
     *
     * @param indexNames  待检索索引名列表（对应 index_name(tenant_id)）
     * @param kbIds       知识库 ID 过滤（对应 filters.kb_id）
     * @param docIds      文档 ID 过滤，可为空（对应 filters.doc_id）
     * @param fulltextExpr 已构建好的全文查询表达式（对应 RagFlow FulltextQueryer.question 产出的 MatchTextExpr.matching_text，
     *                     Lucene query_string 语法；为空则退化为 match_all）
     * @param minimumShouldMatch 最小匹配比例（对应 MatchTextExpr.extra_options.minimum_should_match，如 "30%"；可为空）
     * @param queryVector query 的嵌入向量（用于 KNN，dense_vector cosine）
     * @param size        召回窗口大小（对应 req.size / RERANK_LIMIT）
     * @param topk        向量候选池上限（对应 req.topk）
     * @param similarity  向量相似度阈值（对应 req.similarity）
     * @return 混合检索原始命中
     */
    HybridSearchResult hybridSearch(List<String> indexNames,
                                    List<Long> kbIds,
                                    List<Long> docIds,
                                    String fulltextExpr,
                                    String minimumShouldMatch,
                                    List<Float> queryVector,
                                    int size,
                                    int topk,
                                    double similarity);
}