package com.example.dream.service.core.chat.retriever;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.integration.service.es.HybridSearchResult;
import com.example.dream.service.core.chat.retriever.nlp.FulltextQueryer;
import com.example.dream.service.core.chat.retriever.nlp.MatchTextExpr;
import com.example.dream.service.core.chat.retriever.nlp.RagTokenizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 检索编排层（对应 RagFlow rag/nlp/search.py 中的 {@code Dealer}）。
 *
 * <p><b>本层与具体存储引擎无关</b>：命名不含任何引擎（ES / Infinity / OceanBase）字样，
 * 只负责「不变的检索编排」——分页窗口计算、rerank 策略选择、阈值过滤、结果组装等，这些逻辑对所有引擎完全相同。
 * 「变的存储 I/O」被隔离在 {link DocStoreConnection} 抽象之后（如 ES 实现 {link ElasticsearchDocStore}），
 * 因此新增引擎无需改动本层，符合 RagFlow「把变的部分与不变的部分分离」的分层初衷。</p>
 *
 * <p>本实现严格 1:1 还原 {@code Dealer.retrieval}：query 向量化 -> 混合召回
 * （全文 match + KNN，并对候选做二次纯 KNN 打分，见 {@code Dealer.search}/{@code Dealer._knn_scores}）
 * -> {@code rerank_with_knn} 融合打分 -> 稳定降序 -> 阈值过滤 -> block/page 分页切片 -> 组装
 * chunks 与 doc_aggs 聚合。底层存储 I/O 委托给 {link DocStoreConnection#hybridSearch}。</p>
 *
 * @author dream
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Dealer implements Retriever {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * pagerank 特征字段名（对应 RagFlow common.constants.PAGERANK_FLD = "pagerank_fea"）。
     */
    private static final String PAGERANK_FLD = "pagerank_fea";

    /**
     * tag 特征字段名（对应 RagFlow common.constants.TAG_FLD = "tag_feas"）。
     */
    private static final String TAG_FLD = "tag_feas";

    /**
     * JSON 解析器（用于解析 chunk 的 tag_feas 字符串，对应 parse_tag_features 中的 json.loads）。
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 存储抽象层（对应 RagFlow Dealer.dataStore，类型为抽象基类 DocStoreConnection）。
     * <p>编排层只依赖此抽象，不感知底层引擎；当前注入实现为 {@link ElasticsearchDocStore}。</p>
     */
    private final DocStoreConnection dataStore;

    @Override
    public Map<String, Object> retrieval(String question,
                                         EmbeddingModel embedModel,
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
                                         Object rankFeature) {
        // 1. 参数与初始化
        Map<String, Object> ranks = newKbinfos();
        // 防御性编程：若提问为空，直接返回空结构，避免后续浪费算力。
        if (StringUtils.isBlank(question)) {
            return ranks;
        }

        // 2. 聪明的块式分页（Block-based Pagination）
        // 痛点：分布式搜索引擎（如 Elasticsearch）如果直接对大数据集执行深度分页（High Page Size），性能会极差；且重排模型（Reranker）往往有单次处理上限（如 top=1024）。
        // 解法：代码引入了 RERANK_LIMIT（重排窗口大小）。
        // 它通过计算将前端传入的细粒度 page 转换成搜索引擎需要的“大块（Block）”索引。搜索引擎每次召回一大块数据（例如 1024 条），在内存中重排并过滤后，再通过 global_offset % RERANK_LIMIT 切出当前页真正需要的 page_size 条数。
        int rerankLimit = rerankWindow(pageSize, rerankModel != null ? top : 0);
        page = Math.max(page, 1);
        int globalOffset = (page - 1) * pageSize;

        List<String> idxNames = new ArrayList<>();
        if (!CollectionUtils.isEmpty(userIds)) {
            for (String userId : userIds) {
                idxNames.add(DocTaskConstants.indexName(userId));
            }
        }
        if (CollectionUtils.isEmpty(idxNames)) {
            return ranks;
        }

        // 3. 组装 search 请求并执行混合召回（对应 RagFlow req 构造 + Dealer.search）。
        //    对应 Python req：kb_ids / doc_ids / page=global_offset//RERANK_LIMIT+1 / size=RERANK_LIMIT
        //    / question / topk=top / similarity=similarity_threshold / available_int=1
        int reqPage = globalOffset / rerankLimit + 1;
        HybridSearchResult sres = search(question, embedModel, idxNames, kbIds, docIds,
                reqPage, rerankLimit, top, similarityThreshold, rankFeature);

        // 剔除已删除文档的残留 chunk（对应 RagFlow Dealer._prune_deleted_chunks，在 rerank 前执行）。
        pruneDeletedChunks(sres);
        // sres.total == 0：doc_aggs=[]，直接返回（对应 if sres.total == 0）
        if (sres.getTotal() == 0) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // 4. rerank 融合打分（对应 term_similarity_weight = 1 - vector_similarity_weight）。
        double termSimilarityWeight = 1 - vectorSimilarityWeight;
        double[] sim;
        double[] tsim;
        double[] vsim;
        if (rerankModel != null && sres.getTotal() > 0) {
            // 外部 rerank 模型分支（对应 rerank_by_model）。当前项目未接入外部 reranker，
            // 走桩实现（返回 term/vector 融合的占位），后续接入模型后替换。
            RerankResult rr = rerankByModel(rerankModel, sres, question,
                    termSimilarityWeight, vectorSimilarityWeight, rankFeature);
            sim = rr.sim;
            tsim = rr.tsim;
            vsim = rr.vsim;
        } else {
            // ES 分支：二次纯 KNN 打分（对应 _knn_scores）+ rerank_with_knn 融合。
            // 说明：Python 中还有 Infinity / OceanBase 分支，本项目底层仅 ES，故只实现 ES 路径。
            Map<String, Double> knnScores = dataStore.knnScores(idxNames, kbIds,
                    new ArrayList<>(sres.getIds()), sres.getQueryVector());
            RerankResult rr = rerankWithKnn(sres, question, knnScores,
                    termSimilarityWeight, vectorSimilarityWeight, rankFeature);
            sim = rr.sim;
            tsim = rr.tsim;
            vsim = rr.vsim;
        }

        if (sim.length == 0) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // 5. 稳定降序排序（对应 np.argsort(sim * -1, kind="stable")）。
        Integer[] order = new Integer[sim.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        final double[] simRef = sim;
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -simRef[i]));

        // 6. 阈值过滤（对应 post_threshold + valid_idx）。
        //    vector_similarity_weight <= 0 时阈值对纯 term 分无意义，置 0（对应 Python post_threshold）。
        double postThreshold = vectorSimilarityWeight <= 0 ? 0.0 : similarityThreshold;
        List<Integer> validIdx = new ArrayList<>();
        for (int i : order) {
            if (sim[i] >= postThreshold) {
                validIdx.add(i);
            }
        }
        int filteredCount = validIdx.size();
        ranks.put("total", filteredCount);
        if (filteredCount == 0) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // 7. block/page 分页切片（对应 begin = global_offset % RERANK_LIMIT; end = begin + page_size）。
        int begin = globalOffset % rerankLimit;
        int end = Math.min(begin + pageSize, filteredCount);
        List<Integer> pageIdx = begin >= filteredCount
                ? Collections.emptyList() : validIdx.subList(begin, end);

        int dim = sres.getQueryVector() == null ? 0 : sres.getQueryVector().size();
        String vectorColumn = "q_" + dim + "_vec";

        // 8. 组装 chunks（对应 for i in page_idx: ranks["chunks"].append(d)）。
        List<Map<String, Object>> chunks = chunksOf(ranks);
        List<String> ids = sres.getIds();
        Map<String, Map<String, Object>> fields = sres.getFields();
        for (int i : pageIdx) {
            String id = ids.get(i);
            Map<String, Object> chunk = fields.get(id);
            if (chunk == null) {
                continue;
            }
            Map<String, Object> d = new HashMap<>();
            d.put("chunk_id", id);
            d.put("content_ltks", chunk.get("content_ltks"));
            d.put("content_with_weight", chunk.get("content_with_weight"));
            d.put("doc_id", chunk.get("doc_id"));
            d.put("docnm_kwd", chunk.getOrDefault("docnm_kwd", ""));
            d.put("kb_id", chunk.get("kb_id"));
            d.put("important_kwd", chunk.getOrDefault("important_kwd", new ArrayList<>()));
            d.put("tag_kwd", chunk.getOrDefault("tag_kwd", new ArrayList<>()));
            d.put("image_id", chunk.getOrDefault("img_id", ""));
            d.put("similarity", sim[i]);
            d.put("vector_similarity", vsim[i]);
            d.put("term_similarity", tsim[i]);
            d.put("vector", chunk.getOrDefault(vectorColumn, new ArrayList<>()));
            d.put("positions", chunk.getOrDefault("position_int", new ArrayList<>()));
            d.put("doc_type_kwd", chunk.getOrDefault("doc_type_kwd", ""));
            d.put("mom_id", chunk.getOrDefault("mom_id", ""));
            d.put("row_id", chunk.get("row_id()"));
            chunks.add(d);
        }

        // 9. doc_aggs 聚合（对应 if aggs: 统计每个 doc 的命中数并按 count 降序）。
        if (aggs) {
            Map<String, Map<String, Object>> docAggMap = new LinkedHashMap<>();
            for (int i : validIdx) {
                String id = ids.get(i);
                Map<String, Object> chunk = fields.get(id);
                if (chunk == null) {
                    continue;
                }
                String dnm = String.valueOf(chunk.getOrDefault("docnm_kwd", ""));
                Object did = chunk.get("doc_id");
                Map<String, Object> agg = docAggMap.computeIfAbsent(dnm, k -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("doc_id", did);
                    m.put("count", 0);
                    return m;
                });
                agg.put("count", ((Integer) agg.get("count")) + 1);
            }
            List<Map<String, Object>> docAggs = new ArrayList<>();
            docAggMap.entrySet().stream()
                    .sorted((a, b) -> Integer.compare((Integer) b.getValue().get("count"),
                            (Integer) a.getValue().get("count")))
                    .forEach(e -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("doc_name", e.getKey());
                        m.put("doc_id", e.getValue().get("doc_id"));
                        m.put("count", e.getValue().get("count"));
                        docAggs.add(m);
                    });
            ranks.put("doc_aggs", docAggs);
        } else {
            docAggsEmpty(ranks);
        }

        return ranks;
    }

    /**
     * 混合召回（百分百还原 RagFlow {@code Dealer.search}）。
     *
     * <p>流程：{@code qryr.question(qst, 0.3)} 生成全文表达式 matchText + keywords ->
     * 若 embModel 为空走纯全文召回；否则 query 向量化后走全文+KNN 混合召回 ->
     * total==0 时降级重试（有 doc_id 则去掉全文表达式；否则 min_match=0.1、similarity=0.17 重试）->
     * keywords 细粒度分词扩展 -> 封装 {@link HybridSearchResult}（含 keywords / queryVector）。</p>
     */
    private HybridSearchResult search(String question,
                                      EmbeddingModel embModel,
                                      List<String> idxNames,
                                      List<Long> kbIds,
                                      List<Long> docIds,
                                      int page,
                                      int size,
                                      int topk,
                                      double similarity,
                                      Object rankFeature) {
        // 分页参数（对应 pg = page-1; offset = pg*ps; limit = ps）
        int pg = Math.max(page, 1) - 1;
        int offset = pg * size;

        // 源字段（对应 Python src 默认列表）
        List<String> src = defaultSourceFields();

        // 全文表达式 + keywords（对应 matchText, keywords = qryr.question(qst, min_match=0.3)）
        FulltextQueryer qryr = FulltextQueryer.getInstance();
        FulltextQueryer.QuestionResult qr = qryr.question(question, 0.3);
        MatchTextExpr matchText = qr.matchExpr;
        List<String> keywords = qr.keywords == null ? new ArrayList<>() : new ArrayList<>(qr.keywords);

        DocStoreSearchRequest req = new DocStoreSearchRequest();
        req.setIdxNames(idxNames);
        req.setKbIds(kbIds);
        req.setDocIds(docIds);
        req.setAvailableInt(1);
        req.setSourceFields(src);
        req.setTopk(topk);
        req.setSimilarity(similarity);
        req.setOffset(offset);
        req.setLimit(size);
        req.setRankFeature(rankFeature != null ? toRankFeatureMap(rankFeature) : null);

        HybridSearchResult res;
        List<Float> qVec = new ArrayList<>();
        if (embModel == null) {
            // 纯全文召回分支（对应 emb_mdl is None）
            req.setMatchText(matchText);
            res = dataStore.search(req);
        } else {
            // 混合召回分支：query 向量化（对应 get_vector -> matchDense）
            qVec = embed(embModel, question);
            req.setMatchText(matchText);
            req.setQueryVector(qVec);
            res = dataStore.search(req);

            // total == 0 降级重试（对应 if total == 0）
            if (res.getTotal() == 0) {
                if (!CollectionUtils.isEmpty(docIds)) {
                    // 有 doc_id：去掉全文/向量表达式，仅按 doc_id 过滤召回
                    DocStoreSearchRequest retry = copyReq(req);
                    retry.setMatchText(null);
                    retry.setQueryVector(null);
                    res = dataStore.search(retry);
                } else {
                    // 无 doc_id：min_match=0.1 重建全文表达式，similarity=0.17 重试
                    FulltextQueryer.QuestionResult qr2 = qryr.question(question, 0.1);
                    DocStoreSearchRequest retry = copyReq(req);
                    retry.setMatchText(qr2.matchExpr);
                    retry.setQueryVector(qVec);
                    retry.setSimilarity(0.17);
                    res = dataStore.search(retry);
                }
            }
        }

        // keywords 细粒度分词扩展（对应 for k in keywords: kwds.add(k); 细粒度子词加入）
        RagTokenizer tokenizer = RagTokenizer.getInstance();
        Set<String> kwds = new LinkedHashSet<>();
        for (String k : keywords) {
            kwds.add(k);
            String fg = tokenizer.fineGrainedTokenize(k);
            if (fg == null || fg.trim().isEmpty()) {
                continue;
            }
            for (String kk : WHITESPACE.split(fg.trim())) {
                if (kk.length() < 2 || kwds.contains(kk)) {
                    continue;
                }
                kwds.add(kk);
            }
        }

        res.setQueryVector(qVec);
        res.setKeywords(new ArrayList<>(kwds));
        return res;
    }

    /**
     * 候选窗口大小（百分百还原 RagFlow Dealer._rerank_window）。
     * 用于计算召回/重排窗口大小（window）的辅助函数
     * 计算出一个既能满足性能要求（大约 64 条数据），又绝对不会导致分页错位的最佳窗口大小。
     */
    private int rerankWindow(int pageSize, int top) {
        if (pageSize <= 1) {
            return top > 0 ? Math.min(30, top) : 30;
        }
        int window = (int) Math.ceil(64.0 / pageSize) * pageSize;
        if (top > 0) {
            window = Math.min(window, (int) Math.ceil((double) top / pageSize) * pageSize);
        }
        return window;
    }

    /**
     * 剔除已删除文档的残留 chunk（百分百还原 RagFlow Dealer._prune_deleted_chunks）。
     * 作为一个兜底安全网，过滤掉那些对应的“父文档”已经在数据库中被删除、但“向量分块（Chunks）”因清理延迟而依然残留在向量库中的“幽灵数据”。
     * 通过这种方式，防止用户在聊天或检索时看到已被删除的文档内容。
     *
     * <p>收集命中 chunk 的 doc_id，若全部仍存在则不动；否则原地过滤掉孤儿 chunk，
     * 并同步更新 total / ids / fields / knnScores，保证后续 rerank / 分页 / 聚合一致。
     * 这是一个临时安全网：某些删除路径可能删掉 DB 行却残留向量记录，此处兜底避免
     * 检索命中已删除文档的内容。</p>
     */
    private void pruneDeletedChunks(HybridSearchResult sres) {
        Set<Long> chunkDocIds = new LinkedHashSet<>();
        for (Map<String, Object> field : sres.getFields().values()) {
            if (field == null) {
                continue;
            }
            Object docId = field.get("doc_id");
            if (docId != null) {
                chunkDocIds.add((Long) docId);
            }
        }
        if (CollectionUtils.isEmpty(chunkDocIds)) {
            return;
        }

        Set<Long> existingDocIds = dataStore.existingDocIds(chunkDocIds);
        // 全部存在则无需剪枝（对应 len(existing) == len(set(chunk_doc_ids))）
        if (existingDocIds.size() == chunkDocIds.size()) {
            return;
        }

        // 准备好存储清洗后数据的容器
        List<String> filteredIds = new ArrayList<>();
        Map<String, Map<String, Object>> filteredFields = new LinkedHashMap<>();
        Map<String, Double> filteredKnn = new LinkedHashMap<>();

        int removed = 0;
        for (String chunkId : sres.getIds()) {
            Map<String, Object> chunk = sres.getFields().get(chunkId);
            Object docId = chunk == null ? null : chunk.get("doc_id");
            if (docId == null || !existingDocIds.contains((Long) docId)) {
                removed++;
                continue;
            }
            filteredIds.add(chunkId);
            filteredFields.put(chunkId, chunk);
            if (sres.getKnnScores().containsKey(chunkId)) {
                filteredKnn.put(chunkId, sres.getKnnScores().get(chunkId));
            }
        }
        if (removed > 0) {
            log.warn("Pruned {} stale chunks whose documents no longer exist.", removed);
        }
        sres.setIds(filteredIds);
        sres.setFields(filteredFields);
        sres.setKnnScores(filteredKnn);
        sres.setTotal(filteredIds.size());
    }


    /**
     * 将 ranks 的 doc_aggs 置为空列表（对应 RagFlow ranks["doc_aggs"] = []）。
     */
    private void docAggsEmpty(Map<String, Object> ranks) {
        ranks.put("doc_aggs", new ArrayList<Map<String, Object>>());
    }

    /**
     * 新建 kbinfos 骨架（对应 Python ranks = {"total": 0, "chunks": [], "doc_aggs": {}}）。
     *
     * <p>注意：doc_aggs 初始为空 Map（与 RagFlow 一致，Python 初始为 {}）。仅当 question 为空时
     * 走早退路径原样返回该空 Map；其余返回路径都会经 {@link #docAggsEmpty} 重置为空列表或重建为列表，
     * 从而 1:1 对齐 RagFlow retrieval 的返回结构。</p>
     */
    private Map<String, Object> newKbinfos() {
        Map<String, Object> kbinfos = new HashMap<>();
        kbinfos.put("total", 0);
        kbinfos.put("chunks", new ArrayList<Map<String, Object>>());
        kbinfos.put("doc_aggs", new ArrayList<Map<String, Object>>());
        return kbinfos;
    }

    /**
     * 取 ranks 中的 chunks 列表（对应 ranks["chunks"]）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunksOf(Map<String, Object> ranks) {
        Object v = ranks.get("chunks");
        if (!(v instanceof List)) {
            List<Map<String, Object>> list = new ArrayList<>();
            ranks.put("chunks", list);
            return list;
        }
        return (List<Map<String, Object>>) v;
    }

    /**
     * search 默认源字段（对应 RagFlow Dealer.search 的 src 默认列表）。
     */
    private List<String> defaultSourceFields() {
        return new ArrayList<>(Arrays.asList(
                "docnm_kwd", "content_ltks", "kb_id", "img_id", "title_tks", "important_kwd",
                "position_int", "doc_id", "chunk_order_int", "page_num_int", "top_int",
                "create_timestamp_flt", "knowledge_graph_kwd", "question_kwd", "question_tks",
                "doc_type_kwd", "available_int", "content_with_weight", "mom_id",
                PAGERANK_FLD, TAG_FLD, "row_id()"));
    }

    /**
     * 浅拷贝 search 请求（用于 total==0 降级重试时局部改字段，不污染原请求）。
     */
    private DocStoreSearchRequest copyReq(DocStoreSearchRequest src) {
        DocStoreSearchRequest c = new DocStoreSearchRequest();
        c.setIdxNames(src.getIdxNames());
        c.setKbIds(src.getKbIds());
        c.setDocIds(src.getDocIds());
        c.setAvailableInt(src.getAvailableInt());
        c.setSourceFields(src.getSourceFields());
        c.setMatchText(src.getMatchText());
        c.setQueryVector(src.getQueryVector());
        c.setTopk(src.getTopk());
        c.setSimilarity(src.getSimilarity());
        c.setOffset(src.getOffset());
        c.setLimit(src.getLimit());
        c.setRankFeature(src.getRankFeature());
        return c;
    }

    /**
     * 将 retrieval 入参 rankFeature（Object）转为 {@code Map<String, Double>}，
     * 供底层 ES 查询构建 rank_feature should 子句（对应 Python es_conn.py:224-228）。
     */
    private Map<String, Double> toRankFeatureMap(Object rankFeature) {
        if (!(rankFeature instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (!(k instanceof String key) || key.trim().isEmpty()) {
                continue;
            }
            if (v instanceof Number num && Double.isFinite(num.doubleValue())) {
                result.put(key, num.doubleValue());
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * query 向量化（对应 RagFlow Dealer.get_vector -> emb_mdl.encode_queries）。
     */
    private List<Float> embed(EmbeddingModel embModel, String text) {
        float[] raw = embModel.embed(text);
        List<Float> vec = new ArrayList<>(raw.length);
        for (float v : raw) {
            vec.add(v);
        }
        return vec;
    }

    /**
     * rerank 融合结果三元组（对应 Python (sim, tsim, vsim)）。
     */
    private static final class RerankResult {
        final double[] sim;
        final double[] tsim;
        final double[] vsim;

        RerankResult(double[] sim, double[] tsim, double[] vsim) {
            this.sim = sim;
            this.tsim = tsim;
            this.vsim = vsim;
        }
    }

    /**
     * ES 路径 rerank（百分百还原 RagFlow {@code Dealer.rerank_with_knn}）。
     *
     * <p>用二次 KNN 得到的向量分（knnScores）与本地 term 相似度（{@code FulltextQueryer.token_similarity}）
     * 按权重融合：{@code sim = tkweight*tksim + vtweight*vtsim (+ rank_fea)}。rank_feature 打分本项目
     * 暂以 0 处理（pagerank/tag_fea 未接入），保持结构一致。</p>
     */
    private RerankResult rerankWithKnn(HybridSearchResult sres, String query,
                                       Map<String, Double> knnScores,
                                       double tkweight, double vtweight,
                                       Object rankFeature) {
        FulltextQueryer qryr = FulltextQueryer.getInstance();
        List<String> keywords = qryr.question(query, 0.6).keywords;

        List<String> ids = sres.getIds();
        List<List<String>> insTw = buildInsTw(sres);
        List<Double> tksim = qryr.tokenSimilarity(keywords, insTw);

        // rank feature 打分（对应 rank_fea = self._rank_feature_scores(rank_feature, sres)）
        double[] rankFea = rankFeatureScores(rankFeature, sres);

        int n = ids.size();
        double[] sim = new double[n];
        double[] tsim = new double[n];
        double[] vsim = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i < tksim.size() ? tksim.get(i) : 0.0;
            double v = knnScores.getOrDefault(ids.get(i), 0.0);
            tsim[i] = t;
            vsim[i] = v;
            // 对应 sim = tkweight * tksim + vtweight * vtsim + rank_fea
            sim[i] = tkweight * t + vtweight * v + rankFea[i];
        }
        return new RerankResult(sim, tsim, vsim);
    }

    /**
     * 外部 rerank 模型分支（对应 RagFlow {@code Dealer.rerank_by_model}）。
     *
     * <p>本项目暂未接入外部 reranker，走保守回退：向量分取二次 KNN、term 分取 token_similarity，
     * 按权重融合，行为与无模型时一致，待接入模型后替换 vtsim 的来源。</p>
     */
    private RerankResult rerankByModel(Object rerankModel, HybridSearchResult sres, String query,
                                       double tkweight, double vtweight, Object rankFeature) {
        // 保守回退：向量分沿用 sres 已有的二次 KNN 分（未接入外部 reranker 时通常为空 -> vsim 视为 0），
        // term 分用 token_similarity，按权重融合。待接入模型后，替换 vtsim 为 rerank 模型输出。
        Map<String, Double> knnScores = sres.getKnnScores() == null
                ? new HashMap<>() : sres.getKnnScores();
        return rerankWithKnn(sres, query, knnScores, tkweight, vtweight, rankFeature);
    }

    /**
     * 构造每个候选 chunk 的加权 token 列表（对应 rerank 中 ins_tw 的组装）。
     *
     * <p>规则：{@code content_ltks + title_tks*2 + important_kwd*5 + question_tks*6}。</p>
     */
    private List<List<String>> buildInsTw(HybridSearchResult sres) {
        List<List<String>> insTw = new ArrayList<>();
        for (String id : sres.getIds()) {
            Map<String, Object> field = sres.getFields().get(id);
            List<String> tks = new ArrayList<>();
            if (field != null) {
                tks.addAll(dedupSplit(field.get("content_ltks")));
                addRepeat(tks, splitTokens(field.get("title_tks")), 2);
                addRepeat(tks, asStringList(field.get("important_kwd")), 5);
                addRepeat(tks, splitTokens(field.get("question_tks")), 6);
            }
            insTw.add(tks);
        }
        return insTw;
    }

    /**
     * 保序去重后按空格拆词（对应 content_ltks 的 OrderedDict.fromkeys 处理）。
     */
    private List<String> dedupSplit(Object v) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : splitTokens(v)) {
            if (seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private List<String> splitTokens(Object v) {
        if (v == null) {
            return new ArrayList<>();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(s.split("\\s+")));
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v instanceof String s && !s.isEmpty()) {
            out.add(s);
        }
        return out;
    }

    private void addRepeat(List<String> target, List<String> src, int times) {
        for (int i = 0; i < times; i++) {
            target.addAll(src);
        }
    }

    /**
     * rank feature 打分（百分百还原 RagFlow {@code Dealer._rank_feature_scores}）。
     *
     * <p>综合两部分：
     * <ol>
     *   <li>pagerank 分：直接取每个 chunk 的 {@code pagerank_fea} 字段（缺省 0），作为基础加分；</li>
     *   <li>tag feature 分：将 query 侧的 rank_feature（{@code query_rfea}，来自 tag_query）与 chunk 侧
     *       的 {@code tag_feas} 做归一化余弦式打分，再放大 10 倍。</li>
     * </ol>
     * 最终返回 {@code rank_fea * 10 + pageranks}。当 query_rfea 为空或其模长为 0 时，只返回 pageranks。</p>
     *
     * <p>对应 Python 常量：PAGERANK_FLD = "pagerank_fea"，TAG_FLD = "tag_feas"。</p>
     */
    private double[] rankFeatureScores(Object rankFeature, HybridSearchResult sres) {
        List<String> ids = sres.getIds();
        Map<String, Map<String, Object>> fields = sres.getFields();
        int n = ids.size();

        // pageranks：每个 chunk 的 pagerank_fea（对应 pageranks.append(field.get(PAGERANK_FLD, 0))）
        double[] pageranks = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> chunk = fields.get(ids.get(i));
            pageranks[i] = chunk == null ? 0.0 : toDouble(chunk.get(PAGERANK_FLD), 0.0);
        }

        // query 侧 rank_feature（对应 query_rfea）
        Map<String, Double> queryRfea = asRankFeature(rankFeature);

        // query_rfea 为空 -> 返回 pageranks（对应 if not query_rfea: return 0 + pageranks）
        if (queryRfea.isEmpty()) {
            return pageranks;
        }

        // q_denor = sqrt(sum(s*s for t,s in query_rfea if t != PAGERANK_FLD))
        double qDenorSum = 0.0;
        for (Map.Entry<String, Double> e : queryRfea.entrySet()) {
            if (!PAGERANK_FLD.equals(e.getKey())) {
                double s = e.getValue();
                qDenorSum += s * s;
            }
        }
        double qDenor = Math.sqrt(qDenorSum);
        // q_denor == 0 -> 返回 pageranks（对应 if q_denor == 0: return 0 + pageranks）
        if (qDenor == 0.0) {
            return pageranks;
        }

        double[] rankFea = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Object> chunk = fields.get(ids.get(i));
            Object tagRaw = chunk == null ? null : chunk.get(TAG_FLD);
            if (tagRaw == null) {
                rankFea[i] = 0.0;
                continue;
            }
            Map<String, Double> tagFeas = parseTagFeatures(tagRaw);
            if (tagFeas.isEmpty()) {
                rankFea[i] = 0.0;
                continue;
            }
            double nor = 0.0;
            double denor = 0.0;
            for (Map.Entry<String, Double> e : tagFeas.entrySet()) {
                String t = e.getKey();
                double sc = e.getValue();
                if (queryRfea.containsKey(t)) {
                    nor += queryRfea.get(t) * sc;
                }
                denor += sc * sc;
            }
            rankFea[i] = denor == 0.0 ? 0.0 : nor / Math.sqrt(denor) / qDenor;
        }

        // 对应 return np.array(rank_fea) * 10.0 + pageranks
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = rankFea[i] * 10.0 + pageranks[i];
        }
        return out;
    }

    /**
     * 将 retrieval 入参 rankFeature（Object）转为 {@code Map<String, Double>}（对应 Python query_rfea 字典）。
     * <p>只保留 key 为非空字符串、value 为有限数值的项；null 或非 Map 视为空。</p>
     */
    private Map<String, Double> asRankFeature(Object rankFeature) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (!(rankFeature instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (!(k instanceof String key)) {
                continue;
            }
            key = key.trim();
            if (key.isEmpty()) {
                continue;
            }
            if (v instanceof Boolean) {
                continue;
            }
            if (v instanceof Number num) {
                double d = num.doubleValue();
                if (Double.isFinite(d)) {
                    out.put(key, d);
                }
            }
        }
        return out;
    }

    /**
     * 解析 chunk 的 tag_feas（百分百还原 RagFlow {@code parse_tag_features}，
     * 参数 allow_json_string=True, allow_python_literal=True）。
     *
     * <p>支持传入 Map，或 JSON 字符串（如 {@code {"a":1.0,"b":2}}）。忽略布尔值、非有限数值、空 key。</p>
     */
    private Map<String, Double> parseTagFeatures(Object raw) {
        Map<String, Double> cleaned = new LinkedHashMap<>();
        if (raw == null) {
            return cleaned;
        }
        Map<?, ?> parsed = null;
        if (raw instanceof Map<?, ?> m) {
            parsed = m;
        } else if (raw instanceof String s) {
            String str = s.trim();
            if (str.isEmpty()) {
                return cleaned;
            }
            parsed = tryParseJsonMap(str);
            if (parsed == null) {
                parsed = tryParsePythonLiteralMap(str);
            }
            if (parsed == null) {
                return cleaned;
            }
        } else {
            return cleaned;
        }

        for (Map.Entry<?, ?> e : parsed.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            if (!(k instanceof String key)) {
                continue;
            }
            key = key.trim();
            if (key.isEmpty()) {
                continue;
            }
            if (v instanceof Boolean) {
                continue;
            }
            if (v instanceof Number num) {
                double d = num.doubleValue();
                if (Double.isFinite(d)) {
                    cleaned.put(key, d);
                }
            }
        }
        return cleaned;
    }

    /**
     * 尝试将字符串解析为 JSON 对象 Map（对应 parse_tag_features 中 json.loads 分支）。
     * 解析失败或结果非对象返回 null。
     */
    private Map<?, ?> tryParseJsonMap(String str) {
        try {
            Object obj = JSON_MAPPER.readValue(str, Object.class);
            return obj instanceof Map<?, ?> m ? m : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 尝试将 Python 字面量 dict 字符串（单引号）解析为 Map（对应 ast.literal_eval 分支）。
     * <p>简化处理：将单引号替换为双引号后按 JSON 解析，覆盖常见 {@code {'a': 1.0}} 形态。</p>
     */
    private Map<?, ?> tryParsePythonLiteralMap(String str) {
        try {
            String jsonLike = str.replace('\'', '"');
            Object obj = JSON_MAPPER.readValue(jsonLike, Object.class);
            return obj instanceof Map<?, ?> m ? m : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 安全转 double（对应 Python field.get(PAGERANK_FLD, 0) 的数值语义），非数值/解析失败返回默认值。
     */
    private double toDouble(Object v, double def) {
        if (v == null) {
            return def;
        }
        if (v instanceof Number num) {
            return num.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception ex) {
            return def;
        }
    }
}