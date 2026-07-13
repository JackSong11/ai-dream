package com.example.dream.service.core.chat.retriever;

import com.example.dream.service.core.chat.retriever.nlp.MatchTextExpr;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一次混合检索的请求参数（对应 RagFlow {@code Dealer.search} 内组装、传给
 * {@code DocStoreConnection.search} 的一组参数：filters / matchExprs / offset / limit / src 等）。
 *
 * <p>把 Python 中零散的位置参数收敛为一个结构体，便于编排层 {@link Dealer} 在
 * total==0 降级重试时仅调整少量字段（minimumShouldMatch / similarity）后重发。</p>
 *
 * @author dream
 */
@Data
public class DocStoreSearchRequest {

    /**
     * 索引名列表（对应 idx_names，由租户/用户 id 推导）。
     */
    private List<String> idxNames = new ArrayList<>();

    /**
     * 知识库 id 过滤（对应 kb_ids）。
     */
    private List<Long> kbIds = new ArrayList<>();

    /**
     * 文档 id 过滤，可为空（对应 filters 中的 doc_id）。
     */
    private List<Long> docIds;

    /**
     * available_int 过滤，可为空（对应 filters 中的 available_int，默认 1）。
     */
    private Integer availableInt;

    /**
     * 需要返回的源字段（对应 src）。
     */
    private List<String> sourceFields = new ArrayList<>();

    /**
     * 全文匹配表达式（对应 matchText，由 {@code FulltextQueryer.question} 生成）。
     */
    private MatchTextExpr matchText;

    /**
     * query 的嵌入向量（对应 matchDense.embedding_data）；为空表示纯全文召回（emb_mdl 为 None 分支）。
     */
    private List<Float> queryVector;

    /**
     * 向量候选池上限（对应 topk）。
     */
    private int topk = 1024;

    /**
     * 向量相似度下限（对应 matchDense 的 similarity）。
     */
    private double similarity = 0.1;

    /**
     * 分页偏移（对应 offset = pg * ps）。
     */
    private int offset;

    /**
     * 分页大小（对应 limit = ps）。
     */
    private int limit = 1024;

    /**
     * rank_feature 打分字段（对应 Python es_conn.py 中 rank_feature 参数）。
     *
     * <p>key 为字段名，value 为 boost 权重。对应 RAGFlow {@code Dealer.search} 中的
     * {@code rank_feature} 参数，传递到底层 {@code ESConnection.search}。</p>
     */
    private Map<String, Double> rankFeature;
}