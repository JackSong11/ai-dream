package com.example.dream.service.core.chat.retriever;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.Map;

/**
 * 检索器抽象契约（对应 RagFlow rag/nlp/search.py 中的 {@code Dealer}，即 settings.retriever）。
 *
 * <p>该接口刻意抽象出「检索编排」这一稳定契约：RagFlow 中 {@code settings.retriever} 即
 * {@code Dealer} 实例。本项目对应的编排层实现为 {link Dealer}（<b>引擎无关</b>命名），
 * 其底层存储 I/O 通过 {link DocStoreConnection} 抽象隔离（当前 ES 实现为 {link ElasticsearchDocStore}），
 * 因此新增 Infinity / OceanBase 等引擎只需扩展存储层，编排层与本契约均无需改动。</p>
 *
 * <p>方法与参数命名严格对齐 Python {@code Dealer.retrieval}，便于逐行核对。按「用到什么迁移什么」
 * 的原则，当前仅抽象核心的 {link #retrieval} 主检索方法；retrieval_by_children / retrieval_by_toc /
 * insert_citations / fetch_chunk_vectors 等能力后续用到时再补充到本接口。</p>
 *
 * @author dream
 */
public interface Retriever {

    /**
     * 主检索（对应 Python: async def retrieval(...)）。
     *
     * <p>流程：query 向量化 -> ES 混合召回（全文 + KNN，含二次纯 KNN 打分）->
     * rerank 融合打分 -> 稳定降序 -> 阈值过滤 -> 分页切片 -> 组装 chunks 与 doc_aggs 聚合。</p>
     *
     * @param question               查询问题文本
     * @param embedMdl               嵌入模型（对应 embed_mdl）
     * @param userIds                租户/用户 id 列表，用于推导索引名（对应 tenant_ids）
     * @param kbIds                  知识库 id 过滤（对应 kb_ids）
     * @param page                   页码，从 1 开始（对应 page）
     * @param pageSize               每页大小（对应 top_n）
     * @param similarityThreshold    相似度阈值（对应 similarity_threshold）
     * @param vectorSimilarityWeight 向量相似度权重（对应 vector_similarity_weight）
     * @param docIds                 文档 id 过滤，可为空（对应 doc_ids）
     * @param top                    向量候选池上限（对应 top_k）
     * @param aggs                   是否输出 doc_aggs 聚合（对应 aggs）
     * @param rerankModel            外部 rerank 模型，可为空（对应 rerank_mdl）
     * @param rankFeature            rank feature（tag/pagerank），可为空（对应 rank_feature）
     * @return kbinfos：{@code {"total": int, "chunks": List<Map>, "doc_aggs": List<Map>}}（对应 ranks）
     */
    Map<String, Object> retrieval(String question,
                                  EmbeddingModel embedMdl,
                                  List<String> userIds,
                                  List<Long> kbIds,
                                  int page,
                                  int pageSize,
                                  double similarityThreshold,
                                  double vectorSimilarityWeight,
                                  List<Long> docIds,
                                  int top,
                                  boolean aggs,
                                  Object rerankModel,
                                  Object rankFeature);

}