package com.example.dream.integration.service.es;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ES 混合检索（全文 query_string + 向量 KNN，带 weighted_sum 融合）请求参数。
 *
 * <p>本 DTO 位于 integration 层，<b>不依赖上层</b>（service）的任何类型：全文表达式以
 * {@code queryString + fields + minimumShouldMatch} 的原始形式传入，向量以 {@code queryVector}
 * 传入。对应 RagFlow ESConnection.search 组装的 bool(should=[query_string], knn) 查询。</p>
 *
 * @author dream
 */
@Data
public class HybridSearchRequest {

    /**
     * 索引名列表（对应 idx_names）。
     */
    private List<String> indexNames = new ArrayList<>();

    /**
     * 知识库 id 过滤（对应 kb_id term/terms 过滤）。
     */
    private List<Long> kbIds = new ArrayList<>();

    /**
     * 文档 id 过滤，可为空（对应 doc_id term/terms 过滤）。
     */
    private List<Long> docIds;

    /**
     * available_int 过滤，可为空（对应 available_int term 过滤）。
     */
    private Integer availableInt;

    /**
     * 需要返回的源字段（对应 src / _source）。
     */
    private List<String> sourceFields = new ArrayList<>();

    /**
     * 全文查询字符串（对应 MatchTextExpr.matchingText，Lucene query_string 语法）。
     */
    private String queryString;

    /**
     * 全文匹配字段（含 boost，如 title_tks^10，对应 MatchTextExpr.fields）。
     */
    private List<String> queryFields = new ArrayList<>();

    /**
     * minimum_should_match（对应 MatchTextExpr.extraOptions.minimum_should_match），可为空。
     */
    private Object minimumShouldMatch;

    /**
     * query 的嵌入向量（对应 matchDense.embedding_data）；为空表示纯全文召回。
     */
    private List<Float> queryVector;

    /**
     * KNN 候选数 topk（对应 matchDense.topn）。
     */
    private int topk = 1024;

    /**
     * KNN 相似度下限（对应 matchDense.extra_options.similarity）。
     */
    private double similarity = 0.1;

    /**
     * 全文权重（对应 fusion weights 的第 1 个，默认 0.05）。
     */
    private double textWeight = 0.05;

    /**
     * 向量权重（对应 fusion weights 的第 2 个，默认 0.95）。
     */
    private double vectorWeight = 0.95;

    /**
     * 分页偏移（对应 offset）。
     */
    private int offset;

    /**
     * 分页大小（对应 limit）。
     */
    private int limit = 1024;

    /**
     * rank_feature 打分字段（对应 Python es_conn.py 中 rank_feature 参数）。
     *
     * <p>key 为字段名（如 {@code pagerank_fea} 或 tag 子键），value 为 boost 权重。
     * 对应 RAGFlow：{@code for fld, sc in rank_feature.items(): bool_query.should.append(Q("rank_feature", ...))}。</p>
     */
    private Map<String, Double> rankFeature;
}