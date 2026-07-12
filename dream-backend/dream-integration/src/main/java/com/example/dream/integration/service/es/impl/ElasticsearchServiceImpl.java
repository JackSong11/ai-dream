package com.example.dream.integration.service.es.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.es.HybridSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 服务实现。
 * <p>基于官方 {@link ElasticsearchClient} 实现常用的索引管理、文档 CRUD 与搜索操作。</p>
 *
 * @author dream
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ElasticsearchServiceImpl implements ElasticsearchService {

    private final ElasticsearchClient client;

    // ==================== 索引操作 ====================

    @Override
    public boolean indexExists(String index) {
        try {
            return client.indices().exists(e -> e.index(index)).value();
        } catch (IOException ex) {
            throw new RuntimeException("判断索引是否存在失败: " + index, ex);
        }
    }

    @Override
    public boolean createIndex(String index) {
        try {
            return client.indices().create(c -> c.index(index)).acknowledged();
        } catch (IOException ex) {
            throw new RuntimeException("创建索引失败: " + index, ex);
        }
    }

    @Override
    public boolean deleteIndex(String index) {
        try {
            return client.indices().delete(d -> d.index(index)).acknowledged();
        } catch (IOException ex) {
            throw new RuntimeException("删除索引失败: " + index, ex);
        }
    }

    @Override
    public boolean createChunkIndexIfAbsent(String index, int vectorSize) {
        try {
            if (indexExists(index)) {
                return true;
            }
            // 为分块文档定义映射：向量字段 q_{dim}_vec 为 dense_vector（cosine 相似度）
            String vectorField = "q_" + vectorSize + "_vec";
            String mappingJson = """
                    {
                      "mappings": {
                        "dynamic_templates": [
                          { "int":   { "match": "*_int",   "mapping": { "store": "true", "type": "integer" } } },
                          { "ulong": { "match": "*_ulong", "mapping": { "store": "true", "type": "unsigned_long" } } },
                          { "long":  { "match": "*_long",  "mapping": { "store": "true", "type": "long" } } },
                          { "short": { "match": "*_short", "mapping": { "store": "true", "type": "short" } } },
                          { "numeric": { "match": "*_flt", "mapping": { "store": true, "type": "float" } } },
                          { "tks":  { "match": "*_tks",  "mapping": { "analyzer": "whitespace", "similarity": "scripted_sim", "store": true, "type": "text" } } },
                          { "ltks": { "match": "*_ltks", "mapping": { "analyzer": "whitespace", "store": true, "type": "text" } } },
                          { "kwd":  { "match": "^(.*_(kwd|id|ids|uid|uids)|uid|id)$", "match_pattern": "regex", "mapping": { "similarity": "boolean", "store": true, "type": "keyword" } } },
                          { "dt":   { "match": "^.*(_dt|_time|_at)$", "match_pattern": "regex", "mapping": { "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||yyyy-MM-dd_HH:mm:ss", "store": true, "type": "date" } } },
                          { "nested": { "match": "*_nst", "mapping": { "type": "nested" } } },
                          { "object": { "match": "*_obj", "mapping": { "dynamic": "true", "type": "object" } } },
                          { "string": { "match": "^.*_(with_weight|list)$", "match_pattern": "regex", "mapping": { "index": "false", "store": true, "type": "text" } } },
                          { "rank_feature":  { "match": "*_fea",  "mapping": { "type": "rank_feature" } } },
                          { "rank_features": { "match": "*_feas", "mapping": { "type": "rank_features" } } },
                          { "dense_vector": { "match": "*_512_vec",  "mapping": { "dims": 512,  "index": true, "similarity": "cosine", "type": "dense_vector" } } },
                          { "dense_vector": { "match": "*_768_vec",  "mapping": { "dims": 768,  "index": true, "similarity": "cosine", "type": "dense_vector" } } },
                          { "dense_vector": { "match": "*_1024_vec", "mapping": { "dims": 1024, "index": true, "similarity": "cosine", "type": "dense_vector" } } },
                          { "dense_vector": { "match": "*_1536_vec", "mapping": { "dims": 1536, "index": true, "similarity": "cosine", "type": "dense_vector" } } },
                          { "binary": { "match": "*_bin", "mapping": { "type": "binary" } } }
                        ],
                        "date_detection": true,
                        "properties": {
                          "available_int":        { "type": "integer", "store": true },
                          "content_ltks":         { "type": "text", "store": true, "analyzer": "whitespace" },
                          "content_sm_ltks":      { "type": "text", "store": true, "analyzer": "whitespace" },
                          "content_with_weight":  { "type": "text", "index": false, "store": true },
                          "create_time":          { "type": "date", "store": true, "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd||yyyy-MM-dd_HH:mm:ss" },
                          "create_timestamp_flt": { "type": "float", "store": true },
                          "doc_id":       { "type": "keyword", "store": true, "similarity": "boolean" },
                          "doc_type_kwd": { "type": "keyword", "store": true, "similarity": "boolean" },
                          "docnm_kwd":    { "type": "keyword", "store": true, "similarity": "boolean" },
                          "id":     { "type": "keyword", "store": true, "similarity": "boolean" },
                          "img_id": { "type": "keyword", "store": true, "similarity": "boolean" },
                          "kb_id":  { "type": "keyword", "store": true, "similarity": "boolean" },
                          "lat_lon":      { "type": "geo_point", "store": true },
                          "page_num_int": { "type": "integer", "store": true },
                          "position_int": { "type": "integer", "store": true },
                          "%s": { "type": "dense_vector", "dims": %d, "index": true, "similarity": "cosine" },
                          "title_sm_tks": { "type": "text", "store": true, "analyzer": "whitespace", "similarity": "scripted_sim" },
                          "title_tks":    { "type": "text", "store": true, "analyzer": "whitespace", "similarity": "scripted_sim" },
                          "top_int":      { "type": "integer", "store": true }
                        }
                      }
                    }
                    """.formatted(vectorField, vectorSize);

            try (var mappingStream = new java.io.ByteArrayInputStream(
                    mappingJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                boolean ack = client.indices().create(c -> c
                        .index(index)
                        .withJson(mappingStream)).acknowledged();
                log.info("创建分块索引 index={}, vectorField={}, dims={}, ack={}",
                        index, vectorField, vectorSize, ack);
                return ack;
            }
        } catch (Exception ex) {
            // 并发下可能已被其他线程创建，二次确认存在即视为成功
            if (indexExists(index)) {
                return true;
            }
            throw new RuntimeException("创建分块索引失败: " + index, ex);
        }
    }

    // ==================== 文档操作 ====================

    @Override
    public <T> String saveDocument(String index, String id, T document) {
        try {
            IndexResponse response = client.index(i -> i
                    .index(index)
                    .id(id)
                    .document(document));
            return response.id();
        } catch (IOException ex) {
            throw new RuntimeException("保存文档失败: index=" + index + ", id=" + id, ex);
        }
    }

    @Override
    public <T> T getDocument(String index, String id, Class<T> clazz) {
        try {
            GetResponse<T> response = client.get(g -> g.index(index).id(id), clazz);
            return response.found() ? response.source() : null;
        } catch (IOException ex) {
            throw new RuntimeException("查询文档失败: index=" + index + ", id=" + id, ex);
        }
    }

    @Override
    public <T> boolean updateDocument(String index, String id, T partialDoc) {
        try {
            var response = client.update(u -> u
                    .index(index)
                    .id(id)
                    .doc(partialDoc), (Class<T>) partialDoc.getClass());
            // NoOp 表示无变更，其余视为更新成功
            return response.result() != co.elastic.clients.elasticsearch._types.Result.NoOp;
        } catch (IOException ex) {
            throw new RuntimeException("更新文档失败: index=" + index + ", id=" + id, ex);
        }
    }

    @Override
    public boolean deleteDocument(String index, String id) {
        try {
            var response = client.delete(d -> d.index(index).id(id));
            return response.result() == co.elastic.clients.elasticsearch._types.Result.Deleted;
        } catch (IOException ex) {
            throw new RuntimeException("删除文档失败: index=" + index + ", id=" + id, ex);
        }
    }

    @Override
    public long deleteByTerm(String index, String field, String value) {
        try {
            Query query = Query.of(q -> q.term(t -> t.field(field).value(value)));
            var response = client.deleteByQuery(d -> d.index(index).query(query));
            Long deleted = response.deleted();
            return deleted == null ? 0L : deleted;
        } catch (IOException ex) {
            throw new RuntimeException("按 term 删除文档失败: index=" + index + ", field=" + field, ex);
        }
    }

    @Override
    public <T> boolean bulkSave(String index, Map<String, T> idDocuments) {
        try {
            List<BulkOperation> operations = new ArrayList<>();
            idDocuments.forEach((id, doc) -> operations.add(
                    BulkOperation.of(op -> op.index(idx -> idx.index(index).id(id).document(doc)))));
            BulkResponse response = client.bulk(b -> b.operations(operations));
            if (response.errors()) {
                log.warn("批量写入存在失败项, index={}", index);
                return false;
            }
            return true;
        } catch (IOException ex) {
            throw new RuntimeException("批量保存文档失败: index=" + index, ex);
        }
    }

    // ==================== 搜索操作 ====================

    @Override
    public <T> List<T> matchAll(String index, Class<T> clazz) {
        try {
            SearchResponse<T> response = client.search(s -> s
                    .index(index)
                    .query(q -> q.matchAll(m -> m)), clazz);
            return extractHits(response);
        } catch (IOException ex) {
            throw new RuntimeException("matchAll 查询失败: index=" + index, ex);
        }
    }

    @Override
    public <T> List<T> searchByTerm(String index, String field, String value, Class<T> clazz) {
        try {
            Query query = Query.of(q -> q.term(t -> t.field(field).value(value)));
            SearchResponse<T> response = client.search(s -> s.index(index).query(query), clazz);
            return extractHits(response);
        } catch (IOException ex) {
            throw new RuntimeException("term 查询失败: index=" + index + ", field=" + field, ex);
        }
    }

    @Override
    public <T> List<T> searchByMatch(String index, String field, String text, Class<T> clazz) {
        try {
            Query query = Query.of(q -> q.match(m -> m.field(field).query(text)));
            SearchResponse<T> response = client.search(s -> s.index(index).query(query), clazz);
            return extractHits(response);
        } catch (IOException ex) {
            throw new RuntimeException("match 查询失败: index=" + index + ", field=" + field, ex);
        }
    }

    // ==================== 混合检索（全文 + 向量 KNN）====================

    /**
     * 用于承载 chunk 原始字段的 Map 类型别名（ES 反序列化目标）。
     */
    @SuppressWarnings("unchecked")
    private static final Class<Map<String, Object>> MAP_CLASS = (Class<Map<String, Object>>) (Class<?>) Map.class;

    /**
     * 实现的混合检索（Hybrid Search）核心逻辑。它模拟了开源 RAG 引擎 RagFlow 的检索策略：
     * 结合了传统的全文检索（BM25）与向量检索（KNN Dense Vector），并通过一套降级重试（Fallback）机制以及二次精准打分（Rerank/Refine）来保证召回的召回率与准确度。
     * 四个核心阶段：构建通用过滤器，首次混合检索，空结果降级重试，向量分值修正。
     */
    @Override
    public HybridSearchResult hybridSearch(List<String> indexNames,
                                           List<Long> kbIds,
                                           List<Long> docIds,
                                           String fulltextExpr,
                                           String minimumShouldMatch,
                                           List<Float> queryVector,
                                           int size,
                                           int topk,
                                           double similarity) {
        HybridSearchResult result = new HybridSearchResult();
        if (CollectionUtils.isEmpty(queryVector)) {
            return result;
        }
        if (CollectionUtils.isEmpty(indexNames)) {
            return result;
        }
        result.setQueryVector(new ArrayList<>(queryVector));

        // 1. 构建通用过滤器（Filter Context）
        // 过滤条件：kb_id in kbIds（对应 filters.kb_id），可选 doc_id in docIds（对应 filters.doc_id）
        List<Query> filters = new ArrayList<>();
        /* 等价下面JSON 过滤条件
            {
              "terms": {
                "kb_id": [101, 102]
              }
            }
         */
        if (!CollectionUtils.isEmpty(kbIds)) {
            List<FieldValue> kbValues = new ArrayList<>();
            for (Long kb : kbIds) {
                kbValues.add(FieldValue.of(kb));
            }
            filters.add(Query.of(q -> q.terms(t -> t
                    .field("kb_id")
                    .terms(v -> v.value(kbValues)))));
        }
        if (!CollectionUtils.isEmpty(docIds)) {
            List<FieldValue> docValues = new ArrayList<>();
            for (Long d : docIds) {
                docValues.add(FieldValue.of(d));
            }
            filters.add(Query.of(q -> q.terms(t -> t
                    .field("doc_id")
                    .terms(v -> v.value(docValues)))));
        }

        // available_int = 1 过滤（对应 RagFlow req["available_int"]=1；ES 侧 must_not range<1，即 >=1）。
        // 我们直接以 range{gte:1} 作为 filter，与 RagFlow 语义等价（仅召回可用 chunk）。
        // 通过 .range(...) 构建一个范围查询，指定 .gte(1.0)（Greater Than or Equal，大于等于 1.0）。
        /*
            {
              "range": {
                "available_int": {
                  "gte": 1.0
                }
              }
            }
         */
        filters.add(Query.of(q -> q.range(r -> r.number(n -> n.field("available_int").gte(1.0)))));

        String vectorField = "q_" + queryVector.size() + "_vec";
        boolean hasDoc = !CollectionUtils.isEmpty(docIds);

        // RagFlow Dealer.search（ES 分支）语义：
        // 2. 首次混合检索（First-pass Hybrid Search）
        // 第一次带 fusion 的混合召回：matchText(query_string, minimum_should_match=30%, boost=1-vw=0.05) + matchDense(KNN cosine, boost=vw=0.95)，FusionExpr weighted_sum weights=0.05,0.95。
        SearchOutcome first = runHybridOnce(indexNames, filters, fulltextExpr, vectorField, queryVector,
                size, topk, minimumShouldMatch, (float) similarity, FULLTEXT_BOOST, VECTOR_BOOST, false);
        result.getIds().addAll(first.ids);
        result.getFields().putAll(first.fields);
        result.setTotal(first.total);

        // 3. 空结果降级重试（对应 Dealer.search 的 if total == 0 分支）
        // 策略 A（有明确文档 ID 范围）：如果用户指定了看某些文件，哪怕文本和向量全对不上，也脱掉所有搜索条件，退化为纯过滤查询。把这些文件里的前几条数据直接捞出来保底。
        // 策略 B（无明确文档范围）：放低门槛。将文本的最小匹配度降到 "10%"，并将向量的相似度门槛卡死在极低的 0.17，重新对全库发起一次广撒网的混合检索。
        if (first.total == 0) {
            SearchOutcome retry;
            if (hasDoc) {
                // res = search(src, [], filters, [], ...)：仅过滤，无全文/向量
                retry = runHybridOnce(indexNames, filters, null, null, new ArrayList<>(),
                        size, topk, null, 0.0f, 1.0f, 0.0f, true);
            } else {
                // min_match=0.1 且 matchDense.similarity=0.17 再查一次
                retry = runHybridOnce(indexNames, filters, fulltextExpr, vectorField, queryVector,
                        size, topk, "10%", 0.17f, FULLTEXT_BOOST, VECTOR_BOOST, false);
            }
            result.getIds().clear();
            result.getFields().clear();
            result.getIds().addAll(retry.ids);
            result.getFields().putAll(retry.fields);
            result.setTotal(retry.total);
        }

        // 4. 第二次纯 KNN 打分：对命中的 id 做 KNN，得到干净 cosine 分（对应 Dealer._knn_scores，similarity=0.0）。
        // 原因：混合检索出来的原生 Elastic 分数包含了文本分和各种权重，不是纯粹的向量余弦距离，业务层很难根据这个分数做统一的阈值过滤（例如在前端判断是不是“答非所问”）。
        // 解决：拿着这批确定被召回的 ID，单独调用 knnScores 方法，重新计算一次用户向量和这批文档的纯粹 Cosine 相似度，并塞进 result.setKnnScores 中，供上层做大模型生成前的精准过滤。
        // todo 为什么 ES 不能一次性返回所有分数？这里需要查一下最新版本ES是不是支持同时返回全文分和向量分
        if (!CollectionUtils.isEmpty(queryVector) && !CollectionUtils.isEmpty(result.getIds())) {
            result.setKnnScores(knnScores(indexNames, result.getIds(), vectorField, queryVector));
        }
        return result;
    }

    /**
     * 全文权重（对应 RagFlow bool_query.boost = 1.0 - vector_similarity_weight，fusion weights 首值 0.05）。
     */
    private static final float FULLTEXT_BOOST = 0.05f;

    /**
     * 向量权重（对应 RagFlow fusion weights 次值 0.95）。
     */
    private static final float VECTOR_BOOST = 0.95f;

    /**
     * 仅召回 chunk 的 _source 字段（对应 RagFlow Dealer.search 中的 src 列表，本项目按实际入库字段裁剪）。
     */
    private static final List<String> CHUNK_SOURCE_FIELDS = List.of(
            "docnm_kwd", "content_ltks", "kb_id", "position_int",
            "doc_id", "create_timestamp_flt", "available_int", "content_with_weight");

    /**
     * 单次混合召回（供第一次召回与降级重试复用）。
     * 入参举例：
     * {
     *   "indexNames": ["knowledge_base_v1", "wiki_index"],
     *   "filters": [
     *     { "term": { "status": "published" } },
     *     { "range": { "created_at": { "gte": "2025-01-01" } } }
     *   ],
     *   "question": "如何配置混合检索的权重？",
     *   "vectorField": "content_vector",
     *   "qv": [0.123, -0.456, 0.789, 0.012],
     *   "size": 3,
     *   "topk": 5,
     *   "minShould": "75%",
     *   "knnSimilarity": 0.6,
     *   "fulltextBoost": 0.3,
     *   "vectorBoost": 0.7,
     *   "filterOnly": false
     * }
     *
     * @param indexNames   索引名数组，指定要检索的 Elasticsearch 索引名称列表。支持跨索引联合查询。
     * @param filters      过滤条件数组，强过滤条件（不参与评分，只决定去留）。
     * @param question     全文 query（为空则走 match_all 或纯过滤）
     * @param vectorField  向量字段名(字符串)（为空则不做 KNN）：存储向量数据的字段名。代码中用来做 KNN 向量检索。
     * @param qv           query向量(数组): 即把用户的 question 通过 Embedding 模型转换后的浮点数数组。
     * @param size         召回条数(整数): 最终期望 Elasticsearch 返回给你的文档数量（分页大小）。
     * @param topk         KNN k 值(整数): KNN 向量检索时，在每个分片（Shard）上召回的最相似的邻居数量。
     * @param minShould    (字符串/数字): 对应 ES 的 minimum_should_match。例如 "75%" 表示分词后的关键词至少要匹配上 75% 才能算命中。
     * @param knnSimilarity (浮点数): 向量相似度阈值。只有相似度分数大于该值的文档才会被召回。
     * @param fulltextBoost 全文 boost(浮点数): 全文检索（文本）的分数权重。
     * @param vectorBoost   向量 boost(浮点数): 向量检索的分数权重。在 ES 混合检索中，最终得分通常是 (文本分 * fulltextBoost) + (向量分 * vectorBoost)。
     * @param filterOnly    是否纯过滤（不带全文与向量，对应 doc_id 降级分支）(布尔值): 降级开关。如果为 true，则代码会跳过文本搜索和向量搜索，变成一个纯粹的布尔过滤查询。
     *
     *
     * 出参举例：
     * {
     *   "total": 125,
     *   "ids": ["doc_001", "doc_002", "doc_003"],
     *   "fields": {
     *     "doc_001": {
     *       "title": "Elasticsearch 混合检索指南",
     *       "category": "技术文档",
     *       "content_ltks": "本文介绍如何配置混合检索的权重和参数...",
     *       "_score": 1.452
     *     },
     *     "doc_002": {
     *       "title": "RAG 系统架构设计",
     *       "category": "架构",
     *       "content_ltks": "在 RAG 系统中，混合检索可以显著提升召回率...",
     *       "_score": 1.218
     *     },
     *     "doc_003": {
     *       "title": "向量数据库选型",
     *       "category": "技术文档",
     *       "content_ltks": "合理配置 boost 权重是混合检索的关键...",
     *       "_score": 0.985
     *     }
     *   }
     * }
     * total (长整数): 满足查询条件的总文档数（包含未在本页展示的所有数据）。对应 ES 的 response.hits().total().value()。
     * ids (数组): 包含当前页召回的、按得分从高到低排序的文档唯一主键（_id）列表。
     * fields (对象/Map): 这是一个以文档 id 作为 Key，文档实际内容（source）作为 Value 的复合结构。
     *  内部字段（如 title, category, content_ltks）: 来源于代码中的 CHUNK_SOURCE_FIELDS。
     *  由于使用了 .includes(CHUNK_SOURCE_FIELDS) 过滤，只有指定的这几个核心字段会被从 ES 数据库中取出并返回，避免了传输多余的数据。
     *  _score (浮点数): 核心字段。代码通过 src.put("_score", ...) 把 ES 计算出的混合检索最终得分注入到了文档属性中。分数越高说明文档与查询的相关度越高。
     * @return 单次召回结果
     */
    private SearchOutcome runHybridOnce(List<String> indexNames,
                                        List<Query> filters,
                                        String question,
                                        String vectorField,
                                        List<Float> qv,
                                        int size,
                                        int topk,
                                        String minShould,
                                        float knnSimilarity,
                                        float fulltextBoost,
                                        float vectorBoost,
                                        boolean filterOnly) {
        SearchOutcome outcome = new SearchOutcome();
        try {
            SearchResponse<Map<String, Object>> response = client.search(s -> {
                s.index(indexNames).size(size).source(src -> src.filter(f -> f.includes(CHUNK_SOURCE_FIELDS)));
                s.query(q -> q.bool(b -> {
                    if (!filterOnly && StringUtils.isNotBlank(question)) {
                        // 对应 Dealer.qryr.question -> query_string(minimum_should_match, boost=1-vw)
                        b.must(m -> m.queryString(qs -> {
                            qs.fields("content_ltks").query(question).boost(fulltextBoost);
                            if (StringUtils.isNotBlank(minShould)) {
                                qs.minimumShouldMatch(minShould);
                            }
                            return qs;
                        }));
                    } else if (!filterOnly) {
                        // todo这里为啥要判断 !
                        b.must(m -> m.matchAll(ma -> ma));
                    }
                    if (!CollectionUtils.isEmpty(filters)) {
                        b.filter(filters);
                    }
                    return b;
                }));
                if (!filterOnly && StringUtils.isNotBlank(vectorField) && !CollectionUtils.isEmpty(qv)) {
                    s.knn(k -> k
                            .field(vectorField)
                            .queryVector(qv)
                            .k(topk)
                            .numCandidates(Math.max(topk, size))
                            .similarity(knnSimilarity)
                            .boost(vectorBoost));
                }
                return s;
            }, MAP_CLASS);

            for (Hit<Map<String, Object>> hit : response.hits().hits()) {
                String id = hit.id();
                Map<String, Object> src = hit.source() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(hit.source());
                src.put("_score", hit.score() == null ? 0.0 : hit.score());
                outcome.ids.add(id);
                outcome.fields.put(id, src);
            }
            outcome.total = response.hits().total() == null ? outcome.ids.size() : response.hits().total().value();
        } catch (IOException ex) {
            throw new RuntimeException("混合检索失败: indices=" + indexNames, ex);
        }
        return outcome;
    }

    /**
     * 单次召回的原始命中（内部载体，对应 Dealer.search 一次 dataStore.search 的结果切片）。
     */
    private static final class SearchOutcome {
        long total;
        final List<String> ids = new ArrayList<>();
        final Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
    }

    /**
     * 对给定候选 chunk id 做纯 KNN 打分，返回 id -&gt; cosine 分。
     * <p>对应 RagFlow Dealer._knn_scores：filter 限定 id 集合，取 _score 作为纯向量相似度。</p>
     */
    private Map<String, Double> knnScores(List<String> indexNames, List<String> ids,
                                          String vectorField, List<Float> qv) {
        Map<String, Double> scores = new LinkedHashMap<>();
        try {
            Query idFilter = Query.of(q -> q.ids(i -> i.values(ids)));
            SearchResponse<Map<String, Object>> response = client.search(s -> s
                    .index(indexNames)
                    .size(ids.size())
                    .source(src -> src.fetch(false)) // src.fetch(false)字面意思是 “不要去抓取 _source 块”，所以属于 _source 内部的用户业务字段是一个都不会返回的
                    .knn(k -> k
                            .field(vectorField)
                            .queryVector(qv)
                            .k(ids.size())
                            .numCandidates(ids.size())
                            .similarity(0.0f) // 把相似度门槛设为 0.0。意思是不做任何拦截，只要在列表里，全都要计算出分数。
                            .filter(idFilter)), MAP_CLASS); // 极其重要。这是 ES 8.x 的“预过滤 KNN（Pre-filtered KNN）”。ES 会先通过 idFilter 把这几个指定的文档捞出来，然后只对这几个文档执行向量距离计算。
            for (Hit<Map<String, Object>> hit : response.hits().hits()) {
                scores.put(hit.id(), hit.score() == null ? 0.0 : hit.score());
            }
        } catch (IOException ex) {
            throw new RuntimeException("KNN 二次打分失败: indices=" + indexNames, ex);
        }
        return scores;
    }

    /**
     * 从搜索响应中提取命中文档。
     *
     * @param response 搜索响应
     * @param <T>      文档类型
     * @return 文档列表
     */
    private <T> List<T> extractHits(SearchResponse<T> response) {
        List<T> results = new ArrayList<>();
        for (Hit<T>hit : response.hits().hits()) {
            if (hit.source() != null) {
                results.add(hit.source());
            }
        }
        return results;
    }
}