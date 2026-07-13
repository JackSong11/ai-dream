package com.example.dream.integration.service.es.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.dream.integration.service.es.ElasticsearchService;
import com.example.dream.integration.service.es.HybridSearchRequest;
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

    /**
     * 检索重试次数，对应 Python es_conn.py 的 {@code ATTEMPT_TIME = 2}。
     */
    private static final int ATTEMPT_TIME = 2;

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
                      "settings": {
                        "index": {
                          "number_of_shards": 2,
                          "number_of_replicas": 0,
                          "similarity": {
                            "scripted_sim": {
                              "type": "scripted",
                              "script": {
                                "source": "double idf = Math.log(1+(field.docCount-term.docFreq+0.5)/(term.docFreq + 0.5))/Math.log(1+((field.docCount-0.5)/1.5)); return query.boost * idf * Math.min(doc.freq, 1);"
                              }
                            }
                          }
                        }
                      },
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

    @Override
    public HybridSearchResult hybridSearch(HybridSearchRequest req) {
        HybridSearchResult result = new HybridSearchResult();
        if (req == null || CollectionUtils.isEmpty(req.getIndexNames())) {
            return result;
        }

        // 对齐 Python ESConnection.search：weighted_sum 融合权重（默认 0.5，
        // 有 weights 时取向量权重），bool_query.boost = 1.0 - vector_similarity_weight。
        double vectorSimilarityWeight = req.getVectorWeight();

        // 组装 filter（kb_id / doc_id / available_int）
        List<Query> filters = buildFilters(req.getKbIds(), req.getDocIds(), req.getAvailableInt());

        // 全文 query_string 走 must（对应 Python bool_query.must.append），type=best_fields，boost=1
        boolean hasText = StringUtils.isNotBlank(req.getQueryString());
        List<Query> must = new ArrayList<>();
        if (hasText) {
            must.add(Query.of(q -> q.queryString(qs -> {
                qs.query(req.getQueryString());
                qs.type(co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType.BestFields);
                qs.boost(1.0f);
                if (!CollectionUtils.isEmpty(req.getQueryFields())) {
                    qs.fields(req.getQueryFields());
                }
                if (req.getMinimumShouldMatch() != null) {
                    qs.minimumShouldMatch(String.valueOf(req.getMinimumShouldMatch()));
                }
                return qs;
            })));
        }

        Query boolQuery = Query.of(q -> q.bool(b -> {
            if (!filters.isEmpty()) {
                b.filter(filters);
            }
            if (!must.isEmpty()) {
                b.must(must);
                // 对应 Python：bool_query.boost = 1.0 - vector_similarity_weight
                b.boost((float) (1.0 - vectorSimilarityWeight));
            }
            if (must.isEmpty() && filters.isEmpty()) {
                b.must(m -> m.matchAll(ma -> ma));
            }
            return b;
        }));

        boolean hasVector = !CollectionUtils.isEmpty(req.getQueryVector());
        List<Float> qv = req.getQueryVector();
        int topn = req.getTopk();

        // 对应 Python for i in range(ATTEMPT_TIME) 的重试与超时检测
        IOException lastIoEx = null;
        for (int attempt = 0; attempt < ATTEMPT_TIME; attempt++) {
            try {
                SearchResponse<Map> response = client.search(s -> {
                    s.index(req.getIndexNames())
                            .from(req.getOffset())
                            .size(req.getLimit())
                            .query(boolQuery)
                            .trackTotalHits(t -> t.enabled(true));
                    if (!CollectionUtils.isEmpty(req.getSourceFields())) {
                        s.source(src -> src.filter(f -> f.includes(req.getSourceFields())));
                    }
                    // 向量 KNN：对应 Python s.knn(topn, topn*2, filter=bool_query, similarity)，无 boost。
                    if (hasVector) {
                        String vecField = "q_" + qv.size() + "_vec";
                        s.knn(k -> k.field(vecField)
                                .queryVector(qv)
                                .k(topn)
                                .numCandidates(topn * 2)
                                .filter(boolQuery)
                                .similarity((float) req.getSimilarity()));
                    }
                    return s;
                }, Map.class);

                // 对应 Python：if str(res.timed_out).lower() == "true": raise Exception("Es Timeout.")
                if (Boolean.TRUE.equals(response.timedOut())) {
                    throw new RuntimeException("Es Timeout.");
                }

                fillResult(result, response);
                return result;
            } catch (IOException ex) {
                // 对应 Python ConnectionTimeout 分支：记录并重试
                lastIoEx = ex;
                log.warn("ElasticsearchServiceImpl.hybridSearch IO 异常，第 {} 次重试, index={}",
                        attempt + 1, req.getIndexNames(), ex);
            }
        }

        log.error("ElasticsearchServiceImpl.hybridSearch timeout for {} times! index={}",
                ATTEMPT_TIME, req.getIndexNames());
        throw new RuntimeException("混合检索失败: index=" + req.getIndexNames(), lastIoEx);
    }

    @Override
    public Map<String, Double> knnScore(List<String> indexNames,
                                        List<Long> kbIds,
                                        List<String> chunkIds,
                                        List<Float> queryVector) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(indexNames) || CollectionUtils.isEmpty(chunkIds)
                || CollectionUtils.isEmpty(queryVector)) {
            return scores;
        }
        try {
            List<FieldValue> idValues = new ArrayList<>();
            for (String id : chunkIds) {
                idValues.add(FieldValue.of(id));
            }
            List<Query> filters = new ArrayList<>();
            filters.add(Query.of(q -> q.terms(t -> t.field("id").terms(tv -> tv.value(idValues)))));
            if (!CollectionUtils.isEmpty(kbIds)) {
                List<FieldValue> kbValues = new ArrayList<>();
                for (Long kb : kbIds) {
                    kbValues.add(FieldValue.of(kb));
                }
                filters.add(Query.of(q -> q.terms(t -> t.field("kb_id").terms(tv -> tv.value(kbValues)))));
            }
            String vecField = "q_" + queryVector.size() + "_vec";
            int size = chunkIds.size();

            SearchResponse<Map> response = client.search(s -> s
                    .index(indexNames)
                    .size(size)
                    .source(src -> src.fetch(false))
                    .knn(k -> k.field(vecField)
                            .queryVector(queryVector)
                            .k(size)
                            .numCandidates(size)
                            .filter(filters)
                            .similarity(0.0f)), Map.class);

            for (Hit<Map> hit : response.hits().hits()) {
                scores.put(hit.id(), hit.score() == null ? 0.0 : hit.score());
            }
        } catch (IOException ex) {
            throw new RuntimeException("二次 KNN 打分失败: index=" + indexNames, ex);
        }
        return scores;
    }

    /**
     * 组装 kb_id / doc_id / available_int 过滤条件。
     */
    private List<Query> buildFilters(List<Long> kbIds, List<Long> docIds, Integer availableInt) {
        List<Query> filters = new ArrayList<>();
        if (!CollectionUtils.isEmpty(kbIds)) {
            List<FieldValue> values = new ArrayList<>();
            for (Long kb : kbIds) {
                values.add(FieldValue.of(kb));
            }
            filters.add(Query.of(q -> q.terms(t -> t.field("kb_id").terms(tv -> tv.value(values)))));
        }
        if (!CollectionUtils.isEmpty(docIds)) {
            List<FieldValue> values = new ArrayList<>();
            for (Long doc : docIds) {
                values.add(FieldValue.of(doc));
            }
            filters.add(Query.of(q -> q.terms(t -> t.field("doc_id").terms(tv -> tv.value(values)))));
        }
        if (availableInt != null) {
            // 对齐 Python ESConnection.search：available_int 走 range 逻辑而非 term。
            // v == 0 -> range(available_int < 1)；否则 -> must_not(range(available_int < 1))。
            if (availableInt == 0) {
                filters.add(Query.of(q -> q.range(r -> r.number(n -> n.field("available_int").lt(1.0)))));
            } else {
                Query lt1 = Query.of(q -> q.range(r -> r.number(n -> n.field("available_int").lt(1.0))));
                filters.add(Query.of(q -> q.bool(b -> b.mustNot(lt1))));
            }
        }
        return filters;
    }

    /**
     * 将 ES 命中填充为 {@link HybridSearchResult}（total / ids / fields，字段内含 _score）。
     */
    @SuppressWarnings("unchecked")
    private void fillResult(HybridSearchResult result, SearchResponse<Map> response) {
        long total = response.hits().total() == null ? 0L : response.hits().total().value();
        result.setTotal(total);
        for (Hit<Map> hit : response.hits().hits()) {
            String id = hit.id();
            Map<String, Object> field = hit.source() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(hit.source());
            field.put("_score", hit.score() == null ? 0.0 : hit.score());
            result.getIds().add(id);
            result.getFields().put(id, field);
        }
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