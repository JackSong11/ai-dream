package com.example.dream.integration.service.es;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索（全文 + 向量 KNN）原始命中结果。
 *
 * <p>严格对应 RagFlow rag/nlp/search.py 中 {@code Dealer.SearchResult} 承载的检索原料，
 * 供上层（AsyncChatServiceImpl.retrieval）按 RagFlow 逻辑做加权融合、阈值过滤、分页与聚合。</p>
 *
 * <p>字段语义对齐：
 * <ul>
 *   <li>{@link #total}：命中总数（对应 SearchResult.total）</li>
 *   <li>{@link #ids}：按检索引擎排序的 chunk id 列表（对应 SearchResult.ids）</li>
 *   <li>{@link #fields}：chunk id -> 字段 Map（对应 SearchResult.field，含 _score）</li>
 *   <li>{@link #queryVector}：query 的嵌入向量（对应 SearchResult.query_vector）</li>
 *   <li>{@link #knnScores}：chunk id -> 纯向量余弦分（对应 retrieval 中 _knn_scores 的二次 KNN 打分）</li>
 * </ul>
 * </p>
 *
 * @author dream
 */
@Data
public class HybridSearchResult {

    /**
     * 命中总数（对应 SearchResult.total）。
     */
    private long total;

    /**
     * 按引擎排序的 chunk id 列表（对应 SearchResult.ids）。
     */
    private List<String> ids = new ArrayList<>();

    /**
     * chunk id -> 字段 Map，字段内含 _score 全文分（对应 SearchResult.field）。
     */
    private Map<String, Map<String, Object>> fields = new LinkedHashMap<>();

    /**
     * query 的嵌入向量（对应 SearchResult.query_vector）。
     */
    private List<Float> queryVector = new ArrayList<>();

    /**
     * chunk id -> 纯向量余弦相似度分（对应 retrieval 中 _knn_scores 二次 KNN 打分结果）。
     */
    private Map<String, Double> knnScores = new LinkedHashMap<>();

    /**
     * FulltextQueryer.question 产出的查询关键词（对应 RagFlow Dealer.search 中 {@code _, keywords = qryr.question(...)}）。
     *
     * <p>由查询构建层（{@code FulltextQueryer.question}）在生成全文表达式的同时抽取，
     * 用于编排层 {@code Dealer.rerank_with_knn} 的 term 相似度计算（{@code qryr.token_similarity}），
     * 避免 rerank 时用简单空格切分的粗糙关键词。</p>
     */
    private List<String> keywords = new ArrayList<>();

}