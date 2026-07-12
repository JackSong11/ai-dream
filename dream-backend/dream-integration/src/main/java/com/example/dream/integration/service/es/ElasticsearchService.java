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

    /**
     * 混合检索（全文 query_string + 向量 KNN，带 weighted_sum 融合）。
     *
     * <p>对应 RagFlow ESConnection.search 配合 Dealer.search：构造
     * {@code bool(should=[query_string], filter=[kb_id/doc_id/available_int])} 与可选
     * {@code knn}，按融合分排序返回命中 chunk（id -> 字段 Map，含 {@code _score}）与 total。</p>
     *
     * @param req 混合检索请求参数
     * @return 命中结果（total / ids / fields）
     */
    HybridSearchResult hybridSearch(HybridSearchRequest req);

    /**
     * 二次纯 KNN 打分（对应 RagFlow Dealer._knn_scores）。
     *
     * <p>用 query 向量对给定 chunk id 集合做一次纯 KNN 查询，返回干净的余弦相似度分
     * （chunk id -> score）。ES 首次混合召回不回传 chunk 向量，故用此二次调用补齐向量分。</p>
     *
     * @param indexNames  索引名列表
     * @param kbIds       知识库 id 过滤
     * @param chunkIds    需要打分的 chunk id 集合
     * @param queryVector query 的嵌入向量
     * @return chunk id -> 余弦相似度分
     */
    Map<String, Double> knnScore(List<String> indexNames,
                                 List<Long> kbIds,
                                 List<String> chunkIds,
                                 List<Float> queryVector);

}