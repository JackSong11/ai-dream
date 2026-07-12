package com.example.dream.service.core.chat.retriever;

import com.example.dream.common.constant.DocTaskConstants;
import com.example.dream.integration.service.es.HybridSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 用嵌入模型对 question 编码（对应 Dealer.get_vector -> emb_mdl.encode_queries）
        List<Float> queryVector = encodeQuery(embedModel, question);

        // req.page = global_offset // RERANK_LIMIT + 1, req.size = RERANK_LIMIT, req.topk = top, req.similarity
        int reqPage = globalOffset / rerankLimit + 1;
        // hybridSearch 一次拉取足够窗口（size = reqPage * RERANK_LIMIT），再在本地按 block 取当前块，
        // 以对齐 RagFlow「先取第 reqPage 个 block」的语义（ES 分页 from = (reqPage-1)*size）。
        int fetchSize = reqPage * rerankLimit;
        // sres = await self.search(req, idx_names, kb_ids, embd_mdl); sres = _knn_scores 二次打分
        HybridSearchResult sres = dataStore.hybridSearch(
                idxNames, kbIds, docIds, question, queryVector, fetchSize, top, similarityThreshold);

        // 剔除已删除文档的残留 chunk（对应 RagFlow Dealer._prune_deleted_chunks，在 rerank 前执行）。
        pruneDeletedChunks(sres);
        // sres.total == 0：doc_aggs=[]，直接返回（对应 if sres.total == 0）
        if (sres.getTotal() == 0) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // 取出当前 block（对应 ES 按 req.page 分页得到的 RERANK_LIMIT 条）
        List<String> blockIds = blockIds(sres.getIds(), reqPage, rerankLimit);
        if (blockIds.isEmpty()) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // term_similarity_weight = 1 - vector_similarity_weight
        double termSimilarityWeight = 1 - vectorSimilarityWeight;

        // ES 分支：sim, tsim, vsim = self.rerank_with_knn(sres, question, knn_scores, tkweight, vtweight, rank_feature)
        RerankScores scores = rerankWithKnn(sres, blockIds, question,
                termSimilarityWeight, vectorSimilarityWeight);

        // sim_np = np.array(sim); if size == 0: doc_aggs=[]; return
        double[] sim = scores.sim;
        if (sim.length == 0) {
            docAggsEmpty(ranks);
            return ranks;
        }

        // sorted_idx = np.argsort(sim * -1, kind="stable")（稳定降序）
        Integer[] order = new Integer[sim.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Double.compare(sim[b], sim[a]));

        // post_threshold = 0.0 if vector_similarity_weight <= 0 else similarity_threshold
        double postThreshold = vectorSimilarityWeight <= 0 ? 0.0 : similarityThreshold;

        // valid_idx = [i for i in sorted_idx if sim[i] >= post_threshold]
        List<Integer> validIdx = new ArrayList<>();
        for (Integer i : order) {
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

        // begin = global_offset % RERANK_LIMIT; end = begin + page_size; page_idx = valid_idx[begin:end]
        int begin = globalOffset % rerankLimit;
        int end = Math.min(begin + pageSize, filteredCount);
        List<Integer> pageIdx = begin >= filteredCount ? new ArrayList<>() : validIdx.subList(begin, end);

        int dim = queryVector.size();
        List<Float> zeroVector = new ArrayList<>(Collections.nCopies(dim, 0.0f));

        List<Map<String, Object>> chunks = chunksOf(ranks);
        for (Integer i : pageIdx) {
            String id = blockIds.get(i);
            Map<String, Object> chunk = sres.getFields().getOrDefault(id, new HashMap<>());
            Map<String, Object> d = new HashMap<>();
            d.put("chunk_id", id);
            d.put("content_ltks", chunk.getOrDefault("content_ltks", ""));
            d.put("content_with_weight", chunk.getOrDefault("content_with_weight", ""));
            d.put("doc_id", chunk.getOrDefault("doc_id", ""));
            d.put("docnm_kwd", chunk.getOrDefault("docnm_kwd", ""));
            d.put("kb_id", chunk.get("kb_id"));
            d.put("important_kwd", chunk.getOrDefault("important_kwd", new ArrayList<>()));
            d.put("similarity", sim[i]);
            d.put("vector_similarity", scores.vsim[i]);
            d.put("term_similarity", scores.tsim[i]);
            String vectorField = "q_" + dim + "_vec";
            d.put("vector", chunk.getOrDefault(vectorField, zeroVector));
            d.put("positions", chunk.getOrDefault("position_int", new ArrayList<>()));
            d.put("doc_type_kwd", chunk.getOrDefault("doc_type_kwd", ""));
            // 对齐 RagFlow retrieval 组装的其余字段（本项目暂未入库，取空默认值以保持结构一致）
            d.put("tag_kwd", chunk.getOrDefault("tag_kwd", new ArrayList<>()));
            d.put("image_id", chunk.getOrDefault("img_id", ""));
            d.put("mom_id", chunk.getOrDefault("mom_id", ""));
            d.put("row_id", chunk.get("row_id()"));
            chunks.add(d);
        }

        // aggs：按 docnm_kwd 聚合 doc_aggs（对应 ranks["doc_aggs"] 计数并按 count 降序）
        if (aggs) {
            Map<String, int[]> counter = new LinkedHashMap<>();
            Map<String, Object> docIdOf = new LinkedHashMap<>();
            for (Integer i : validIdx) {
                String id = blockIds.get(i);
                Map<String, Object> chunk = sres.getFields().getOrDefault(id, new HashMap<>());
                String dnm = strOf(chunk.getOrDefault("docnm_kwd", ""));
                counter.computeIfAbsent(dnm, k -> new int[1])[0]++;
                docIdOf.putIfAbsent(dnm, chunk.getOrDefault("doc_id", ""));
            }
            List<Map<String, Object>> docAggs = new ArrayList<>();
            counter.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue()[0], x.getValue()[0]))
                    .forEach(e -> {
                        Map<String, Object> agg = new HashMap<>();
                        agg.put("doc_name", e.getKey());
                        agg.put("doc_id", docIdOf.get(e.getKey()));
                        agg.put("count", e.getValue()[0]);
                        docAggs.add(agg);
                    });
            ranks.put("doc_aggs", docAggs);
        } else {
            docAggsEmpty(ranks);
        }

        return ranks;
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
     *
     * <p>收集命中 chunk 的 doc_id，若全部仍存在则不动；否则原地过滤掉孤儿 chunk，
     * 并同步更新 total / ids / fields / knnScores，保证后续 rerank / 分页 / 聚合一致。
     * 这是一个临时安全网：某些删除路径可能删掉 DB 行却残留向量记录，此处兜底避免
     * 检索命中已删除文档的内容。</p>
     */
    private void pruneDeletedChunks(HybridSearchResult sres) {
        Set<String> chunkDocIds = new LinkedHashSet<>();
        for (Map<String, Object> field : sres.getFields().values()) {
            if (field == null) {
                continue;
            }
            Object docId = field.get("doc_id");
            if (docId != null && !String.valueOf(docId).isBlank()) {
                chunkDocIds.add(String.valueOf(docId));
            }
        }
        if (chunkDocIds.isEmpty()) {
            return;
        }

        Set<String> existingDocIds = dataStore.existingDocIds(chunkDocIds);
        // 全部存在则无需剪枝（对应 len(existing) == len(set(chunk_doc_ids))）
        if (existingDocIds.size() == chunkDocIds.size()) {
            return;
        }

        List<String> filteredIds = new ArrayList<>();
        Map<String, Map<String, Object>> filteredFields = new LinkedHashMap<>();
        Map<String, Double> filteredKnn = new LinkedHashMap<>();
        int removed = 0;
        for (String chunkId : sres.getIds()) {
            Map<String, Object> chunk = sres.getFields().get(chunkId);
            Object docId = chunk == null ? null : chunk.get("doc_id");
            if (chunk == null || docId == null || !existingDocIds.contains(String.valueOf(docId))) {
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
     * 从命中 id 列表中取第 reqPage 个 block（每 block rerankLimit 条），对应 ES 按 req.page 分页。
     */
    private List<String> blockIds(List<String> ids, int reqPage, int rerankLimit) {
        int from = (reqPage - 1) * rerankLimit;
        if (from >= ids.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(from + rerankLimit, ids.size());
        return new ArrayList<>(ids.subList(from, to));
    }

    /**
     * 用嵌入模型对 query 编码为向量（对应 Dealer.get_vector -> emb_mdl.encode_queries）。
     */
    private List<Float> encodeQuery(Object embedModel, String question) {
        List<Float> vec = new ArrayList<>();
        if (embedModel instanceof EmbeddingModel em) {
            float[] arr = em.embed(question);
            for (float v : arr) {
                vec.add(v);
            }
        }
        return vec;
    }

    /**
     * 混合检索结果的 rerank（百分百还原 RagFlow Dealer.rerank_with_knn，与具体引擎无关）。
     *
     * <p>sim = tkweight * tksim + vtweight * vtsim + rank_fea。其中：
     * <ul>
     *   <li>tksim：query 关键词与 chunk token 集合的 term 相似度（content_ltks 空格分词做交集占比近似，
     *       对应 qryr.token_similarity 的语义）；</li>
     *   <li>vtsim：二次纯 KNN 的 cosine 向量分（对应 knn_scores[chunk_id]）；</li>
     *   <li>rank_fea：tag 特征 + pagerank，本项目暂无，恒为 0（对应 _rank_feature_scores 无特征分支）。</li>
     * </ul>
     * token 权重放大与 RagFlow 一致：content + title*2 + important*5 + question*6，本项目仅有 content_ltks。</p>
     */
    private RerankScores rerankWithKnn(HybridSearchResult sres, List<String> ids, String query,
                                       double tkweight, double vtweight) {
        // _, keywords = self.qryr.question(query)：以空格切分作为关键词近似
        Set<String> keywords = new LinkedHashSet<>();
        for (String w : query.toLowerCase().split("\\s+")) {
            if (!w.isBlank()) {
                keywords.add(w);
            }
        }

        int n = ids.size();
        double[] tksim = new double[n];
        double[] vtsim = new double[n];
        double[] sim = new double[n];
        for (int i = 0; i < n; i++) {
            String id = ids.get(i);
            Map<String, Object> field = sres.getFields().getOrDefault(id, new HashMap<>());
            // content_ltks + title_tks*2 + important_kwd*5 + question_tks*6（本项目仅 content_ltks）
            List<String> tks = new ArrayList<>();
            for (String w : strOf(field.getOrDefault("content_ltks", "")).toLowerCase().split("\\s+")) {
                if (!w.isBlank()) {
                    tks.add(w);
                }
            }
            tksim[i] = tokenSimilarity(keywords, tks);
            vtsim[i] = sres.getKnnScores().getOrDefault(id, 0.0);
            // rank_fea = 0（本项目暂无 tag_fea / pagerank）
            sim[i] = tkweight * tksim[i] + vtweight * vtsim[i];
        }
        return new RerankScores(sim, tksim, vtsim);
    }

    /**
     * term 相似度近似（对应 RagFlow FulltextQueryer.token_similarity 的语义简化）。
     * <p>无 idf 权重时退化为「命中关键词占关键词总数的比例」，取值 [0,1]。</p>
     */
    private double tokenSimilarity(Set<String> keywords, List<String> tks) {
        if (keywords.isEmpty() || tks == null || tks.isEmpty()) {
            return 0.0;
        }
        Set<String> tkSet = new HashSet<>(tks);
        int hit = 0;
        for (String k : keywords) {
            if (tkSet.contains(k)) {
                hit++;
            }
        }
        return (double) hit / keywords.size();
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
        kbinfos.put("doc_aggs", new HashMap<String, Object>());
        return kbinfos;
    }

    /**
     * 取 kbinfos 中的 chunks 列表。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunksOf(Map<String, Object> kbinfos) {
        return (List<Map<String, Object>>) kbinfos.get("chunks");
    }

    private String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * rerank 打分结果（对应 RagFlow rerank_with_knn 返回的 sim, tsim, vsim 三元组）。
     */
    private static final class RerankScores {
        final double[] sim;
        final double[] tsim;
        final double[] vsim;

        RerankScores(double[] sim, double[] tsim, double[] vsim) {
            this.sim = sim;
            this.tsim = tsim;
            this.vsim = vsim;
        }
    }
}