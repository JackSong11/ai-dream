package com.example.dream.integration.service.es.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.example.dream.integration.service.es.ElasticsearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
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
                      "dynamic": true,
                      "properties": {
                        "doc_id":   { "type": "keyword" },
                        "kb_id":    { "type": "keyword" },
                        "docnm_kwd":{ "type": "keyword" },
                        "title_tks":{ "type": "text" },
                        "content_with_weight": { "type": "text" },
                        "content_ltks":        { "type": "text" },
                        "important_kwd":       { "type": "keyword" },
                        "available_int":       { "type": "integer" },
                        "create_time":         { "type": "keyword" },
                        "create_timestamp_flt":{ "type": "double" },
                        "%s": {
                          "type": "dense_vector",
                          "dims": %d,
                          "index": true,
                          "similarity": "cosine"
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