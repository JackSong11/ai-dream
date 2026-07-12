
问题：api/db/services/dialog_service.py:759 详细帮我分析ragFlow这个项目retriever.retrieval相关功能代码的整个设计思维，比如分哪些层，以及为什么这么写代码，我只知道最底层存储层用的ES


# RAGFlow `retriever.retrieval` 架构设计分析

> 本文档基于 RAGFlow 项目源码，深入分析 `retriever.retrieval` 相关功能的整体架构分层、核心设计思维及关键实现细节。

---

## 一、整体分层架构

RAGFlow 的检索系统可以清晰地分为 **5 层**，从上到下依次为：

```
┌─────────────────────────────────────────────────┐
│  L1: 业务编排层 (Application/Orchestration)       │
│  dialog_service.py / agent/tools/retrieval.py    │
├─────────────────────────────────────────────────┤
│  L2: 高级检索策略层 (Advanced Retrieval)           │
│  DeepResearcher / KGSearch / Tavily             │
├─────────────────────────────────────────────────┤
│  L3: 检索核心层 (Dealer - Retrieval Engine)       │
│  rag/nlp/search.py → Dealer.retrieval()         │
├─────────────────────────────────────────────────┤
│  L4: 查询处理层 (Query Processing)               │
│  FulltextQueryer / term_weight / synonym        │
├─────────────────────────────────────────────────┤
│  L5: 存储抽象层 (DocStoreConnection)              │
│  ES / Infinity / OceanBase / OpenSearch         │
└─────────────────────────────────────────────────┘
```

---

## 二、逐层详细分析

### L1: 业务编排层

**核心文件**：

- `api/db/services/dialog_service.py` — 对话场景的检索编排入口
- `agent/tools/retrieval.py` — Agent 工具场景的检索编排入口

#### 设计思路

这一层负责 **编排整个对话检索流程**，是最高层的调用入口。在 `dialog_service.py:633` 处获取全局 `retriever` 单例：

```python
retriever = settings.retriever  # 全局单例，类型是 Dealer
```

#### 为什么这么设计

- **关注点分离**：编排层不关心底层用什么存储引擎、怎么做混合检索，只负责"何时检索、用什么参数检索"。
- **多策略分支**：根据配置决定走哪条检索路径——普通检索、Deep Research、Web 搜索、知识图谱、TOC 增强、子块回溯。
- **全局单例**：`settings.retriever` 在 `common/settings.py:371` 初始化为 `search.Dealer(docStoreConn)`，整个进程共享一个实例，避免重复创建连接。

#### 检索流程编排

以 `dialog_service.py` 中 `async_chat()` 函数（L571 起）为例，检索流程如下：

```
1. 主检索:     retriever.retrieval()              → 混合检索获得候选 chunks
2. TOC 增强:   retriever.retrieval_by_toc()       → 利用目录结构补充 chunks（可选）
3. 子块回溯:   retriever.retrieval_by_children()  → 将子 chunk 合并回父 chunk
4. Web 搜索:   Tavily.retrieve_chunks()           → 互联网搜索补充（可选）
5. 知识图谱:   kg_retriever.retrieval()           → 图谱检索补充（可选）
```

对应的代码位置（`dialog_service.py`）：

| 步骤 | 代码行号 | 说明 |
|------|---------|------|
| 主检索 | L759-773 | `retriever.retrieval()` 核心调用 |
| TOC 增强 | L774-777 | `retriever.retrieval_by_toc()` 可选 |
| 子块回溯 | L778 | `retriever.retrieval_by_children()` |
| Web 搜索 | L779-783 | `Tavily` 外部搜索 |
| 知识图谱 | L784-790 | `kg_retriever.retrieval()` 可选 |

---

### L2: 高级检索策略层

**核心文件**：

- `rag/advanced_rag/tree_structured_query_decomposition_retrieval.py` — Deep Research（深度研究）
- `rag/graphrag/search.py` — 知识图谱检索（KGSearch）
- `rag/utils/tavily_conn.py` — 互联网搜索（Tavily）

#### 三个关键组件

##### 1. DeepResearcher（TreeStructuredQueryDecompositionRetrieval）

通过 LLM 将复杂问题分解为子查询，多轮迭代检索后综合答案。它接收一个 `partial(retriever.retrieval, ...)` 作为检索回调（见 `dialog_service.py:724-734`），将底层 `Dealer.retrieval` 包装为可调用对象：

```python
reasoner = DeepResearcher(
    chat_mdl,
    prompt_config,
    partial(
        retriever.retrieval,
        embd_mdl=embd_mdl,
        tenant_ids=tenant_ids,
        kb_ids=dialog.kb_ids,
        page=1,
        page_size=dialog.top_n,
        similarity_threshold=0.2,
        vector_similarity_weight=0.3,
        doc_ids=attachments,
    ),
    internet_enabled=use_web_search,
)
```

##### 2. KGSearch（继承自 Dealer）

在向量 + 全文检索基础上增加知识图谱检索能力——先查实体和关系，再结合结构化图信息补充上下文。

```python
class KGSearch(Dealer):
    # 继承 Dealer 的全部基础检索能力
    # 扩展: query_rewrite(), _ent_info_from_(), _relation_info_from_() 等图谱方法
```

##### 3. Tavily

第三方互联网搜索服务，作为独立的检索通道，返回结果直接合并到 `kbinfos["chunks"]` 中。

#### 为什么这么设计

- **策略模式**：不同检索策略通过注入 `kb_retrieve` 回调实现解耦，`DeepResearcher` 不需要知道底层用 ES 还是 Infinity。
- **继承复用**：`KGSearch` 继承 `Dealer`，复用全部基础检索能力，只扩展图谱相关逻辑。
- **渐进增强**：主检索返回基础结果，高级策略作为"插件"叠加增强，互不干扰。

---

### L3: 检索核心层（Dealer）

**核心文件**：`rag/nlp/search.py`

`Dealer` 是整个检索系统的核心引擎，构造函数接收 `DocStoreConnection`：

```python
class Dealer:
    def __init__(self, dataStore: DocStoreConnection):
        self.qryr = query.FulltextQueryer()  # 查询处理层
        self.dataStore = dataStore            # 存储抽象层
```

#### Dealer.retrieval() 核心流程

`Dealer.retrieval()` 方法（L549-745）是检索系统的核心入口，执行流程如下：

```
1. 计算分页窗口 (_rerank_window)
2. 构造检索请求 req（包含 kb_ids, doc_ids, question, vector 等）
3. 调用 self.search() → 执行混合检索（全文 + 向量 + 融合）
4. 过滤已删除文档的 chunks (_prune_deleted_chunks)
5. Rerank（三种路径，按存储引擎分支）
6. 按相似度阈值过滤 + 分页截断
7. 组装返回结果
```

#### Rerank 的三种分支设计

Rerank 阶段是理解整个架构的关键，根据存储引擎和是否配置 rerank 模型分为三条路径：

| 存储引擎 | Rerank 策略 | 方法 | 原因 |
|---------|------------|------|------|
| **ES** | 二次 KNN 查询 | `rerank_with_knn()` | ES 的混合检索分数（`weighted_sum` 融合）不是纯余弦相似度，需二次 KNN-only 调用获取真实 cosine 分数，再与本地 term 分数加权合并 |
| **Infinity** | 直接用融合分数 | 直接取 `_score` | Infinity 内部已做归一化，融合分数即可用 |
| **OceanBase** | 本地向量重排 | `rerank()` | OB 返回 chunk 向量，可本地计算 |
| **任意 + rerank 模型** | 模型重排 | `rerank_by_model()` | 使用外部 rerank 模型（如 Cohere）重新打分 |

**为什么 ES 路径要二次 KNN 调用**：

ES 的 `weighted_sum` 融合把 BM25 和 KNN 分数混合后不再可分，无法从中提取纯向量相似度。所以 `_knn_scores()`（L363）用候选 chunk ID 再做一次纯 KNN 查询，让 ES 直接返回 cosine 分数，避免传输 chunk 向量到应用层（节省带宽）。

```python
# ES 路径的 Rerank 流程（retrieval 方法 L641-654）
else:
    # ES path: ask ES for the clean cosine score via a second
    # KNN-only call filtered by the candidate ids, then merge it
    # with locally computed term similarity using the user's weight.
    knn_scores = await self._knn_scores(sres, idx_names, kb_ids)
    sim, tsim, vsim = self.rerank_with_knn(
        sres, question, knn_scores,
        term_similarity_weight, vector_similarity_weight,
        rank_feature=rank_feature,
    )
```

#### Dealer.search() 混合检索

`Dealer.search()` 方法（L134-245）负责执行底层的混合检索：

1. 用 `FulltextQueryer.question()` 将用户问题转为全文检索表达式 `MatchTextExpr`
2. 用 `emb_mdl.encode_queries()` 获取 query 向量，构造 `MatchDenseExpr`
3. 用 `FusionExpr("weighted_sum", topk, {"weights": "0.05,0.95"})` 做混合融合（向量权重 95%）
4. 将三种表达式一起传给 `self.dataStore.search()` 执行
5. 如果结果为空，降低 `min_match` 和 `similarity` 重新搜索（降级兜底）

```python
# 混合检索核心逻辑（search 方法 L192-229）
matchText, keywords = self.qryr.question(qst, min_match=0.3)
matchDense = await self.get_vector(qst, embd_mdl, topk, req.get("similarity", 0.1))
fusionExpr = FusionExpr("weighted_sum", topk, {"weights": "0.05,0.95"})
matchExprs = [matchText, matchDense, fusionExpr]

res = await thread_pool_exec(
    self.dataStore.search, src, highlightFields, filters,
    matchExprs, orderBy, offset, limit, idx_names, kb_ids,
    rank_feature=rank_feature
)

# 降级兜底：结果为空时降低阈值重试
if total == 0:
    matchText, _ = self.qryr.question(qst, min_match=0.1)
    matchDense.extra_options["similarity"] = 0.17
    res = await thread_pool_exec(...)
```

#### 其他关键方法

| 方法 | 行号 | 功能 |
|------|------|------|
| `insert_citations()` | L251 | 将 LLM 回答分段编码，与 chunk 向量做混合相似度匹配，自动插入引用标记 `[ID:x]` |
| `retrieval_by_toc()` | L839 | 取最高分文档的目录结构，让 LLM 判断哪些章节相关，补充更多 chunks |
| `retrieval_by_children()` | L902 | 将有 `mom_id` 的子 chunk 合并回父 chunk，提供更完整的上下文 |
| `fetch_chunk_vectors()` | L396 | Citation 专用：按需拉取 chunk 向量，避免主检索路径传输大量向量数据 |
| `_knn_scores()` | L363 | ES 路径专用：二次 KNN 查询获取纯 cosine 分数 |
| `rerank_with_knn()` | L434 | ES 路径专用：合并 KNN 分数与 term 分数 |
| `rerank()` | L461 | OceanBase 路径：本地向量重排 |
| `rerank_by_model()` | L494 | 外部 rerank 模型重排 |

---

### L4: 查询处理层

**核心文件**：

- `rag/nlp/query.py` — `FulltextQueryer` 全文查询器
- `rag/nlp/term_weight.py` — `term_weight.Dealer` 词频权重计算
- `rag/nlp/synonym.py` — `synonym.Dealer` 同义词扩展

#### FulltextQueryer

`FulltextQueryer` 负责将自然语言转为结构化检索表达式：

```python
class FulltextQueryer(QueryBase):
    def __init__(self):
        self.tw = term_weight.Dealer()    # 词频权重计算
        self.syn = synonym.Dealer(...)     # 同义词扩展
        self.query_fields = [              # 字段 boost 配置
            "title_tks^10",
            "title_sm_tks^5",
            "important_kwd^30",
            "important_tks^20",
            "question_tks^20",
            "content_ltks^2",
            "content_sm_ltks",
        ]
```

#### 为什么这么设计

- **分词 + 权重**：不同字段给予不同 boost（标题 10x、关键词 30x、问题 20x），体现领域知识对检索重要性的判断。
- **同义词扩展**：通过 Redis 存储同义词表，查询时自动扩展，提升召回率。
- **多语言支持**：中文繁简转换、全角半角归一化、阿拉伯数字归一化等。
- **输出标准化**：生成引擎无关的 `MatchTextExpr` 对象，由存储层各自翻译为自己的查询 DSL。

#### 查询处理流程

```
用户原始问题
    │
    ▼
繁简转换 + 全角半角归一化
    │
    ▼
同义词扩展 (synonym.Dealer)
    │
    ▼
分词 + 词频权重计算 (term_weight.Dealer)
    │
    ▼
生成 MatchTextExpr（引擎无关的全文检索表达式）
```

---

### L5: 存储抽象层

**核心文件**：`common/doc_store/doc_store_base.py`

#### DocStoreConnection 抽象基类

`DocStoreConnection` 是抽象基类（ABC），定义了统一的存储操作接口：

```python
class DocStoreConnection(ABC):
    @abstractmethod
    def search(self, select_fields, highlight_fields, condition,
               match_expressions, order_by, offset, limit,
               index_names, dataset_ids, ...) -> abstract

    @abstractmethod
    def get(self, data_id, index_name, dataset_ids) -> abstract

    @abstractmethod
    def insert(self, documents, index_name, dataset_id) -> abstract

    @abstractmethod
    def delete(self, condition, index_name, dataset_id) -> abstract
    # ... 更多抽象方法
```

#### 表达式对象体系

存储抽象层通过一组引擎无关的表达式对象来描述检索语义（策略模式的体现）：

| 表达式类 | 用途 | 关键属性 |
|---------|------|---------|
| `MatchTextExpr` | 全文检索 | `fields`, `matching_text`, `topn` |
| `MatchDenseExpr` | 向量检索 | `vector_column_name`, `embedding_data`, `distance_type` |
| `MatchSparseExpr` | 稀疏向量检索 | `sparse_data`, `distance_type` |
| `MatchTensorExpr` | 张量检索 | `query_data`, `query_data_type` |
| `FusionExpr` | 融合表达式 | `method`（如 `"weighted_sum"`）, `fusion_params` |
| `OrderByExpr` | 排序表达式 | `fields` 列表 |
| `SparseVector` | 稀疏向量数据 | `indices`, `values` |

统一类型别名：

```python
MatchExpr = MatchTextExpr | MatchDenseExpr | MatchSparseExpr | MatchTensorExpr | FusionExpr
```

#### 四种存储引擎实现

在 `common/settings.py:308-322` 根据环境变量 `DOC_ENGINE` 选择具体实现：

| DOC_ENGINE | 实现类 | 基类 | 文件 |
|-----------|--------|------|------|
| `elasticsearch` | `ESConnection` | `ESConnectionBase` | `rag/utils/es_conn.py` |
| `infinity` | `InfinityConnection` | `InfinityConnectionBase` | `rag/utils/infinity_conn.py` |
| `opensearch` | `OSConnection` | `ESConnectionBase` | `rag/utils/opensearch_conn.py` |
| `oceanbase` | `OBConnection` | `OBConnectionBase` | `rag/utils/ob_conn.py` |
| `seekdb` | `OBConnection` | `OBConnectionBase` | `rag/utils/ob_conn.py` |

#### 为什么用抽象基类 + 表达式对象

- **引擎无关**：上层 `Dealer` 只操作 `MatchTextExpr` / `MatchDenseExpr` 等抽象对象，不知道底层是 ES 还是 Infinity。
- **可替换性**：通过 `DOC_ENGINE` 环境变量一键切换存储引擎，业务代码零修改。
- **各自优化**：ES 用 `_search` API + `knn` 查询，Infinity 用自己的 SDK，OB 用 SQL，每种引擎翻译表达式时可以做特定优化。

---

## 三、初始化链路

整个检索系统的初始化在 `common/settings.py` 的 `init_settings()` 函数中完成：

```python
# common/settings.py

def init_settings():
    # ... 其他初始化 ...

    # 第一步：根据 DOC_ENGINE 环境变量创建存储连接（L303-322）
    global DOC_ENGINE, DOC_ENGINE_INFINITY, DOC_ENGINE_OCEANBASE, docStoreConn
    DOC_ENGINE = os.environ.get("DOC_ENGINE", "elasticsearch").strip()

    if DOC_ENGINE.lower() == "elasticsearch":
        docStoreConn = rag.utils.es_conn.ESConnection()
    elif DOC_ENGINE.lower() == "infinity":
        docStoreConn = rag.utils.infinity_conn.InfinityConnection()
    elif DOC_ENGINE.lower() == "opensearch":
        docStoreConn = rag.utils.opensearch_conn.OSConnection()
    elif DOC_ENGINE.lower() in ["oceanbase", "seekdb"]:
        docStoreConn = rag.utils.ob_conn.OBConnection()

    # 第二步：用 docStoreConn 创建检索引擎（L370-374）
    global retriever, kg_retriever
    retriever = search.Dealer(docStoreConn)
    kg_retriever = kg_search.KGSearch(docStoreConn)
```

初始化顺序图：

```
init_settings()
    │
    ├─① 读取 DOC_ENGINE 环境变量
    │
    ├─② 创建 docStoreConn（DocStoreConnection 的具体实现）
    │     ├── ESConnection        (DOC_ENGINE=elasticsearch)
    │     ├── InfinityConnection   (DOC_ENGINE=infinity)
    │     ├── OSConnection         (DOC_ENGINE=opensearch)
    │     └── OBConnection         (DOC_ENGINE=oceanbase)
    │
    ├─③ 创建 retriever = Dealer(docStoreConn)
    │     └── Dealer 持有 dataStore 引用 + 内部创建 FulltextQueryer
    │
    └─④ 创建 kg_retriever = KGSearch(docStoreConn)
          └── KGSearch 继承 Dealer，复用全部基础检索能力
```

---

## 四、核心数据流

以一次完整的对话检索为例，数据从用户输入到最终返回的全流程：

```
用户提问 "什么是向量数据库？"
    │
    ▼ L1: 业务编排层 (dialog_service.py)
    │
    ├── 问题预处理
    │   ├── 多轮对话改写 (full_question)
    │   ├── 跨语言扩展 (cross_languages)
    │   └── 关键词提取增强 (keyword_extraction)
    │
    ├── 判断检索策略
    │   ├── reasoning=True → DeepResearcher（多轮子查询分解）
    │   └── reasoning=False → 直接调用 retriever.retrieval()
    │
    ▼ L3: 检索核心层 (Dealer.retrieval)
    │
    ├── 构造检索请求
    │   ├── 计算分页窗口 (_rerank_window)
    │   └── 封装 req dict
    │
    ├── Dealer.search() 混合检索
    │   │
    │   ▼ L4: 查询处理层 (FulltextQueryer)
    │   │
    │   ├── 全文表达式: MatchTextExpr
    │   │   └── 分词 + 同义词 + 权重
    │   │
    │   ├── 向量表达式: MatchDenseExpr
    │   │   └── embd_mdl.encode_queries() → query 向量
    │   │
    │   └── 融合表达式: FusionExpr("weighted_sum", weights="0.05,0.95")
    │
    │   ▼ L5: 存储抽象层 (DocStoreConnection)
    │   │
    │   └── dataStore.search(matchExprs) → 原始检索结果
    │
    ├── 后处理
    │   ├── _prune_deleted_chunks()  过滤已删除文档
    │   ├── Rerank（按引擎分支）
    │   │   ├── ES: _knn_scores() + rerank_with_knn()
    │   │   ├── Infinity: 直接用融合分数
    │   │   ├── OceanBase: rerank() 本地重排
    │   │   └── + rerank 模型: rerank_by_model()
    │   ├── 相似度阈值过滤
    │   └── 分页截断
    │
    ▼ L1: 返回业务编排层
    │
    ├── 可选增强
    │   ├── retrieval_by_toc()     TOC 目录增强
    │   ├── retrieval_by_children() 子块回溯
    │   ├── Tavily Web 搜索
    │   └── KGSearch 知识图谱
    │
    ├── 引用标注
    │   ├── _hydrate_chunk_vectors() 按需拉取向量
    │   └── insert_citations()       自动插入 [ID:x]
    │
    └── 返回最终结果
        ├── chunks: 检索到的文档片段
        ├── doc_aggs: 文档聚合统计
        └── citation: 引用标注
```

---

## 五、核心设计思维总结

### 1. 策略模式贯穿始终

从存储引擎选择到 Rerank 策略到高级检索策略，都通过注入 / 继承实现可替换：

- **存储引擎**：通过 `DOC_ENGINE` 环境变量 + 工厂方法选择
- **Rerank 策略**：在 `Dealer.retrieval()` 中按引擎类型分支
- **高级检索**：`DeepResearcher` 通过注入 `partial(retriever.retrieval)` 实现解耦

### 2. 混合检索（Hybrid Search）

全文 (BM25) + 向量 (KNN) + 融合 (weighted_sum)，三种引擎各自实现融合逻辑：

- ES 使用 `weighted_sum` fusion，权重 "0.05,0.95"（向量占 95%）
- Infinity 内部归一化后融合
- OceanBase 本地计算混合分数

### 3. 延迟加载向量

ES 路径主检索不拉 chunk 向量（节省带宽），仅在需要 citation 时按需 fetch：

```python
# dialog_service.py:854
await _hydrate_chunk_vectors(retriever, kbinfos.get("chunks", []),
                             tenant_ids, dialog.kb_ids)
```

这个设计的核心思想是：**不是每个检索结果都需要做引用标注**，而向量数据量巨大，延迟到真正需要时再拉取可以显著减少 ES 和应用之间的数据传输。

### 4. 渐进增强

主检索 → TOC 增强 → 子块回溯 → Web 搜索 → 知识图谱，每一步都是可选的增强插件：

- 每个增强步骤独立可控（通过 `prompt_config` 开关）
- 增强结果合并到同一个 `kbinfos` 结构中
- 任何一步失败不影响整体流程

### 5. 降级兜底

系统在多个层面设计了降级策略：

| 场景 | 降级策略 | 代码位置 |
|------|---------|---------|
| 混合检索结果为空 | 降低 `min_match`（0.3→0.1）和 `similarity` 重试 | `search()` L218-229 |
| 父 chunk 不存在 | 回退到子 chunk 列表 | `retrieval_by_children()` L931 |
| fetch_chunk_vectors 失败 | 降级使用零向量，citation 使用占位符 | `_hydrate_chunk_vectors()` L99-101 |
| SQL 检索失败 | 回退到向量检索 | `async_chat()` L669-670 |

### 6. 全局单例 + 依赖注入

`settings.retriever` 全局唯一，通过 `partial(retriever.retrieval, ...)` 注入给 `DeepResearcher`，解耦调用方与实现：

```python
# 将 retriever.retrieval 的部分参数固定后注入
partial(
    retriever.retrieval,
    embd_mdl=embd_mdl,
    tenant_ids=tenant_ids,
    kb_ids=dialog.kb_ids,
    page=1,
    page_size=dialog.top_n,
    similarity_threshold=0.2,
    vector_similarity_weight=0.3,
    doc_ids=attachments,
)
```

这种方式使得 `DeepResearcher` 只需要一个 `Callable` 接口，不需要知道底层是 `Dealer` 还是其他实现。

---

## 六、关键类关系图

```
                    ┌──────────────────┐
                    │  DocStoreConnection (ABC)  │
                    │  doc_store_base.py         │
                    └────────┬─────────┘
                             │ implements
              ┌──────────────┼──────────────┬──────────────┐
              ▼              ▼              ▼              ▼
     ESConnectionBase  InfinityConnBase  OBConnBase  OSConnection
              ▲              ▲              ▲
              │              │              │
       ESConnection  InfinityConnection  OBConnection

                    ┌──────────────────┐
                    │     Dealer       │
                    │ rag/nlp/search.py│
                    └────────┬─────────┘
                             │ holds
                    ┌────────┴─────────┐
                    │                  │
              ┌─────▼─────┐    ┌──────▼──────┐
              │ Fulltext  │    │ DocStore    │
              │ Queryer   │    │ Connection  │
              └───────────┘    └─────────────┘

                    ┌──────────────────┐
                    │   KGSearch       │
                    │ graphrag/search  │
                    └────────┬─────────┘
                             │ extends
                    ┌────────▼─────────┐
                    │     Dealer       │
                    └──────────────────┘

                    ┌──────────────────┐
                    │ DeepResearcher   │
                    │ advanced_rag/    │
                    └────────┬─────────┘
                             │ holds (callback)
                    ┌────────▼─────────┐
                    │ partial(retriever│
                    │   .retrieval)    │
                    └──────────────────┘
```

---

## 七、配置与扩展

### 环境变量

| 环境变量 | 默认值 | 说明 |
|---------|-------|------|
| `DOC_ENGINE` | `elasticsearch` | 存储引擎选择：`elasticsearch` / `infinity` / `opensearch` / `oceanbase` / `seekdb` |

### 检索参数

| 参数 | 位置 | 说明 |
|------|------|------|
| `similarity_threshold` | Dialog 模型 | 相似度阈值，低于此值的结果被过滤 |
| `vector_similarity_weight` | Dialog 模型 | 向量相似度权重（0-1），term 权重 = 1 - 此值 |
| `top_n` | Dialog 模型 | 返回的 chunk 数量（page_size） |
| `top_k` | Dialog 模型 | KNN 检索的候选数量 |
| `rerank_id` | Dialog 模型 | Rerank 模型 ID，为空则不使用模型重排 |

### 扩展新存储引擎

1. 继承 `DocStoreConnection`，实现所有抽象方法
2. 在 `common/settings.py` 的 `init_settings()` 中添加新引擎分支
3. 无需修改上层任何代码（`Dealer`、`dialog_service.py` 等）