package com.example.dream.service.core.chat.retriever.nlp;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全文查询构建器（百分百还原 RagFlow {@code rag/nlp/query.py} 的 {@code FulltextQueryer}，
 * 含其基类 {@code common/query_base.py} 的 {@code QueryBase} 工具方法）。
 *
 * <p>{#question} 将用户 query 转成结构化全文匹配表达式（{@link MatchTextExpr}）并返回关键词
 * {@code keywords}。处理流程：中英文加空格 -> 特殊字符清洗/繁简/全半角/小写 -> {@code rmWWW} 去疑问停用词
 * -> 中/英分支：分词（{@link RagTokenizer}）+ 词权重（{@link TermWeight}）+ 同义词（{@link Synonym}）+
 * 细粒度分词 -> 组装带 boost/邻近({@code ~2})/{@code minimum_should_match} 的表达式。</p>
 *
 * @author dream
 */
public final class FulltextQueryer {

    // ===== QueryBase 相关正则 =====
    private static final Pattern SPLIT_WS = Pattern.compile("[ \\t]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern ALPHA_ONLY = Pattern.compile("[a-zA-Z]+$");
    private static final Pattern SUB_SPECIAL = Pattern.compile("([:\\{\\}/\\[\\]\\-\\*\\?\"\\(\\)\\|\\+~\\^])");

    private static final Pattern RM_ZH = Pattern.compile(
            "是*(怎么办|什么样的|哪家|一下|那家|请问|啥样|咋样了|什么时候|何时|何地|何人|是否|是不是|多少|哪里|怎么|哪儿|怎么样|如何|哪些|是啥|啥是|啊|吗|呢|吧|咋|什么|有没有|呀|谁|哪位|哪个)是*");
    private static final Pattern RM_EN1 = Pattern.compile(
            "(^| )(what|who|how|which|where|why)('re|'s)? ", Pattern.CASE_INSENSITIVE);
    private static final Pattern RM_EN2 = Pattern.compile(
            "(^| )('s|'re|is|are|were|was|do|does|did|don't|doesn't|didn't|has|have|be|there|you|me|your|my|mine|just|please|may|i|should|would|wouldn't|will|won't|done|go|for|with|so|the|a|an|by|i'm|it's|he's|she's|they|they're|you're|as|by|on|in|at|up|out|down|of|to|or|and|if) ",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ESCAPABLE = Pattern.compile(
            "[ :|\\r\\n\\t,，。？?/`!！&^%()\\[\\]{}<>*~'\"\\\\]+");

    // ===== question 内部正则 =====
    private static final Pattern PREFIX_SIGN = Pattern.compile("^[\\+-]");
    private static final Pattern STRIP_QUOTE = Pattern.compile("[ \\\\\"'^]");
    private static final Pattern SKIP_TK = Pattern.compile("[.^+\\(\\)-].*");
    private static final Pattern FINE_SKIP = Pattern.compile("[0-9a-z\\.\\+#_\\*-]+$");
    private static final Pattern SM_CLEAN = Pattern.compile(
            "[ ,\\./;'\\[\\]\\\\`~!@#$%\\^&\\*\\(\\)=\\+_<>\\?:\"\\{\\}\\|，。；‘’【】、！￥……（）——《》？：“”-]+");

    /**
     * query_fields，与 RagFlow 完全一致。
     */
    private final List<String> queryFields = List.of(
            "title_tks^10", "title_sm_tks^5", "important_kwd^30", "important_tks^20",
            "question_tks^20", "content_ltks^2", "content_sm_ltks");

    private final RagTokenizer tokenizer;
    private final TermWeight tw;
    private final Synonym syn;

    private static volatile FulltextQueryer instance;

    public static FulltextQueryer getInstance() {
        if (instance == null) {
            synchronized (FulltextQueryer.class) {
                if (instance == null) {
                    instance = new FulltextQueryer();
                }
            }
        }
        return instance;
    }

    private FulltextQueryer() {
        this.tokenizer = RagTokenizer.getInstance();
        this.tw = TermWeight.getInstance();
        this.syn = Synonym.getInstance();
    }

    // ==================== QueryBase 工具方法 ====================

    /**
     * 对应 QueryBase.is_chinese。
     * 通过排除法来判断一段文本（line）是否“不是纯英文”或“大概率是中文/非英文”。
     */
    static boolean isChinese(String line) {
        // 按一个或多个空格或制表符（Tab）切分，得到的单词/词片列表
        String[] arr = SPLIT_WS.split(line);
        // 短文本直接放行
        if (arr.length <= 3) {
            return true;
        }
        // 统计“非纯英文”部分的数量
        int e = 0;
        for (String t : arr) {
            // 检查当前的词 t 是否完全由纯英文字母组成（不分大小写）。
            if (!ALPHA_ONLY.matcher(t).matches()) {
                e++;
            }
        }
        // 如果非英文部分的比例大于或等于 70%，函数返回 True；否则返回 False。
        return e * 1.0 / arr.length >= 0.7;
    }

    /**
     * 对应 QueryBase.sub_special_char。
     */
    static String subSpecialChar(String line) {
        String noQuote = line.replace("'", "");
        return SUB_SPECIAL.matcher(noQuote).replaceAll("\\\\$1").trim();
    }

    /**
     * 对应 QueryBase.rmWWW。
     * 过滤掉文本中的“疑问词”和“常见虚词（停用词）”，以此来提取文本的核心关键词。
     */
    static String rmWWW(String txt) {
        if (StringUtils.isBlank(txt)) {
            return txt;
        }
        String otxt = txt;
        txt = RM_ZH.matcher(txt).replaceAll("");
        txt = RM_EN1.matcher(txt).replaceAll(" ");
        txt = RM_EN2.matcher(txt).replaceAll(" ");
        if (StringUtils.isBlank(txt)) {
            txt = otxt;
        }
        return txt;
    }

    /**
     * 对应 QueryBase.add_space_between_eng_zh。
     * 在字符串中的中文与英文（或中英文数字组合）之间自动添加一个空格。
     * 在 RAG（检索增强生成）系统中，这段代码看似只是做了小小的格式美化，但实际上它对提升检索准确率（Embedding / Tokenization）和最终大模型的生成质量有着非常关键的底层影响。它的核心作用主要体现在以下三个阶段：
     * 1. 文本分词（Tokenization）阶段：防止语义粘连
     *  大模型和向量模型（Embedding）在处理文本前，都需要先进行“分词”。
     *      坏情况：如果没有空格，像 "使用GPT4模型" 这样的文本，分词器可能会把它切分成 ["使用", "GPT4模型"] 甚至把中英文混在一起当成一个怪异的未知词（OOV）。
     *      好情况：加入空格变成 "使用 GPT4 模型" 后，分词器能够极其精准地切分为 ["使用", "GPT4", "模型"]。
     *  为什么这很重要？ 大模型对独立 Token（如 "GPT4"）的理解，远比对粘连 Token（如 "GPT4模型"）的理解要深刻。规范的分词能让大模型在后续的理解和生成中少走弯路。
     * 2. 向量检索（Retrieval）阶段：大幅提升召回率
     *  RAG 的第一步是将用户的问题和知识库文档都转化为向量（Embedding），然后进行相似度匹配。绝大多数主流的 Embedding 模型，在训练时输入的数据大都保持了良好的排版规范。
     *      搜索冲突：如果用户提问 "什么是 GPT4"（带空格），而你的知识库切片（Chunk）里存的是 "什么是GPT4"（没空格）。
     *      后果：由于两者的分词结果不同，计算出来的向量空间距离就会变远。这会导致原本最匹配的知识库文档，因为一个小小的空格问题，排名掉到了后面，甚至根本无法被检索出来。
     *  通过在数据清洗（ETL）阶段统一加上空格，可以确保用户输入与知识库内容在向量化时标准一致，显著提升检索的召回率（Recall）。
     * 3. 生成与显示（Generation & UI）阶段：提升可读性
     *  RAG 检索出内容后，会喂给大模型（LLM）让它生成最终答案。
     *      对大模型而言：喂给它排版规范、中英有别的上下文（Context），有助于它生成逻辑更清晰、语法更规范的回复。
     */
    static String addSpaceBetweenEngZh(String txt) {
        // [\u4e00-\u9fa5]：这是用来匹配任意单个中文字符的 Unicode 编码范围。
        // 捕获组 () 与 复制引用 \1 \2：圆括号把匹配到的文本分成“组”。在替换时，\1 代表第一个括号里的内容，\2 代表第二个括号里的内容，中间加个空格 " " 就实现了插入空格的目的。
        // 1. 处理 英文+数字 紧跟 中文 的情况：匹配目标：比如 "iPhone14上市了"。替换结果：变成 "iPhone14 上市了"。
        // 左半边 ([A-Za-z]+[0-9]+)：匹配至少一个英文字母后面紧跟至少一个数字（如 iPhone14），并存为第 1 组。
        // 右半边 ([\u4e00-\u9fa5]+)：匹配连续的中文字符（如 上市了），并存为第 2 组。
        txt = txt.replaceAll("([A-Za-z]+[0-9]+)([\\u4e00-\\u9fa5]+)", "$1 $2");
        // 2. 处理 纯英文 紧跟 中文 的情况
        txt = txt.replaceAll("([A-Za-z])([\\u4e00-\\u9fa5]+)", "$1 $2");
        // 3. 处理 中文 紧跟 英文+数字 的情况
        txt = txt.replaceAll("([\\u4e00-\\u9fa5]+)([A-Za-z]+[0-9]+)", "$1 $2");
        // 4. 处理 中文 紧跟 纯英文 的情况
        txt = txt.replaceAll("([\\u4e00-\\u9fa5]+)([A-Za-z])", "$1 $2");
        return txt;
    }

    // ==================== question ====================

    /**
     * question 的返回：MatchTextExpr（可能为 null）+ keywords。
     */
    public static final class QuestionResult {
        public final MatchTextExpr matchExpr;
        public final List<String> keywords;

        public QuestionResult(MatchTextExpr matchExpr, List<String> keywords) {
            this.matchExpr = matchExpr;
            this.keywords = keywords;
        }
    }

    /**
     * 构建全文查询（百分百还原 FulltextQueryer.question）。
     */
    public QuestionResult question(String txt, double minMatch) {
        String originalQuery = txt;
        txt = addSpaceBetweenEngZh(txt);

        // 文本清洗：在分词（Tokenization）之前，文本清晰能大大提升文本匹配的准确率。
        // tokenizer.strQ2B：全角字符转半角（如 Ａ→A，１→1），确保字符统一为ASCII范围。可以搜下全角和半角的区别
        // tokenizer.tradi2simp：繁体中文转简体中文（如 查詢→查询），统一中文表述。
        // ESCAPABLE：用正则将所有特殊字符替换为空格。这些字符包括：
        txt = ESCAPABLE.matcher(tokenizer.tradi2simp(tokenizer.strQ2B(txt.toLowerCase())))
                .replaceAll(" ").trim();
        String otxt = txt;
        txt = rmWWW(txt);

        if (!isChinese(txt)) {
            // ===== 非中文分支 =====
            String[] tks = tokenizer.tokenize(txt).trim().isEmpty()
                    ? new String[0] : WHITESPACE.split(tokenizer.tokenize(txt).trim());
            List<String> keywords = new ArrayList<>();
            for (String t : tks) {
                if (!t.isEmpty()) {
                    keywords.add(t);
                }
            }
            List<TermWeight.TW> tksW = tw.weights(new ArrayList<>(List.of(tks)), false);
            // 清洗：去 [ \"'^]、去开头 +/-、trim、过滤空
            List<TermWeight.TW> cleaned = new ArrayList<>();
            for (TermWeight.TW e : tksW) {
                String tk = STRIP_QUOTE.matcher(e.token).replaceAll("");
                if (tk.isEmpty()) {
                    continue;
                }
                tk = PREFIX_SIGN.matcher(tk).replaceFirst("").trim();
                if (tk.isEmpty()) {
                    continue;
                }
                cleaned.add(new TermWeight.TW(tk, e.weight));
            }

            List<String> syns = new ArrayList<>();
            int limit = Math.min(256, cleaned.size());
            for (int idx = 0; idx < limit; idx++) {
                TermWeight.TW e = cleaned.get(idx);
                List<String> synList = new ArrayList<>();
                for (String s : syn.lookup(e.token)) {
                    String s2 = tokenizer.tokenize(s).replace("'", "");
                    synList.add(s2);
                    keywords.add(s2);
                }
                List<String> quoted = new ArrayList<>();
                for (String s : synList) {
                    if (!s.trim().isEmpty()) {
                        quoted.add(String.format("\"%s\"^%.4f", s, e.weight / 4.0));
                    }
                }
                syns.add(String.join(" ", quoted));
            }

            List<String> q = new ArrayList<>();
            for (int idx = 0; idx < limit; idx++) {
                TermWeight.TW e = cleaned.get(idx);
                String tk = e.token;
                if (tk.isEmpty() || SKIP_TK.matcher(tk).lookingAt()) {
                    continue;
                }
                String synPart = idx < syns.size() ? syns.get(idx) : "";
                q.add(String.format("(%s^%.4f %s)", tk, e.weight, synPart));
            }
            for (int i = 1; i < cleaned.size(); i++) {
                String left = cleaned.get(i - 1).token.trim();
                String right = cleaned.get(i).token.trim();
                if (left.isEmpty() || right.isEmpty()) {
                    continue;
                }
                double w = Math.max(cleaned.get(i - 1).weight, cleaned.get(i).weight) * 2;
                q.add(String.format("\"%s %s\"^%.4f", cleaned.get(i - 1).token, cleaned.get(i).token, w));
            }
            if (q.isEmpty()) {
                q.add(txt);
            }
            String query = String.join(" ", q);
            Map<String, Object> extra = new HashMap<>();
            extra.put(MatchTextExpr.KEY_MINIMUM_SHOULD_MATCH, Math.min(3, Math.round((double) keywords.size() / 10)));
            extra.put(MatchTextExpr.KEY_ORIGINAL_QUERY, originalQuery);
            return new QuestionResult(new MatchTextExpr(queryFields, query, 100, extra), keywords);
        }

        // ===== 中文分支（下一批追加）=====
        return questionChinese(txt, otxt, originalQuery, minMatch);
    }

    private boolean needFineGrainedTokenize(String tk) {
        if (tk.length() < 3) {
            return false;
        }
        return !FINE_SKIP.matcher(tk).matches();
    }

    /**
     * question 的中文分支（对应 Python 中 is_chinese 为真的后半段）。
     */
    private QuestionResult questionChinese(String txt, String otxt, String originalQuery, double minMatch) {
        List<String> qs = new ArrayList<>();
        List<String> keywords = new ArrayList<>();

        List<String> splitTks = tw.split(txt);
        int splitLimit = Math.min(256, splitTks.size());
        for (int si = 0; si < splitLimit; si++) {
            String tt = splitTks.get(si);
            if (tt == null || tt.isEmpty()) {
                continue;
            }
            keywords.add(tt);
            List<TermWeight.TW> twts = tw.weights(new ArrayList<>(List.of(tt)), true);
            List<String> synsTt = syn.lookup(tt);
            if (!synsTt.isEmpty() && keywords.size() < 32) {
                keywords.addAll(synsTt);
            }

            // 按权重降序
            List<TermWeight.TW> sorted = new ArrayList<>(twts);
            sorted.sort((a, b) -> Double.compare(b.weight, a.weight));

            List<String> tms = new ArrayList<>();
            for (TermWeight.TW e : sorted) {
                String tk0 = e.token;
                double w = e.weight;
                List<String> sm;
                if (needFineGrainedTokenize(tk0)) {
                    String fg = tokenizer.fineGrainedTokenize(tk0);
                    sm = fg.trim().isEmpty() ? new ArrayList<>()
                            : new ArrayList<>(List.of(fg.trim().split("\\s+")));
                } else {
                    sm = new ArrayList<>();
                }
                List<String> smClean = new ArrayList<>();
                for (String m : sm) {
                    String c = SM_CLEAN.matcher(m).replaceAll("");
                    c = subSpecialChar(c);
                    if (c.length() > 1) {
                        smClean.add(c);
                    }
                }
                sm = new ArrayList<>();
                for (String m : smClean) {
                    if (m.length() > 1) {
                        sm.add(m);
                    }
                }

                if (keywords.size() < 32) {
                    keywords.add(STRIP_QUOTE.matcher(tk0).replaceAll(""));
                    keywords.addAll(sm);
                }

                List<String> tkSyns = new ArrayList<>();
                for (String s : syn.lookup(tk0)) {
                    tkSyns.add(subSpecialChar(s));
                }
                if (keywords.size() < 32) {
                    for (String s : tkSyns) {
                        if (!s.isEmpty()) {
                            keywords.add(s);
                        }
                    }
                }
                List<String> tkSynsFg = new ArrayList<>();
                for (String s : tkSyns) {
                    if (s != null && !s.isEmpty()) {
                        String fg = tokenizer.fineGrainedTokenize(s);
                        tkSynsFg.add(fg.indexOf(' ') > 0 ? "\"" + fg + "\"" : fg);
                    }
                }

                if (keywords.size() >= 32) {
                    break;
                }

                String tk = subSpecialChar(tk0);
                if (tk.indexOf(' ') > 0) {
                    tk = "\"" + tk + "\"";
                }
                if (!tkSynsFg.isEmpty()) {
                    tk = String.format("(%s OR (%s)^0.2)", tk, String.join(" ", tkSynsFg));
                }
                if (!sm.isEmpty()) {
                    tk = String.format("%s OR \"%s\" OR (\"%s\"~2)^0.5",
                            tk, String.join(" ", sm), String.join(" ", sm));
                }
                if (!tk.trim().isEmpty()) {
                    tms.add(String.format("(%s)^%s", tk, formatNum(w)));
                }
            }

            String tmsStr = String.join(" ", tms);

            if (twts.size() > 1) {
                tmsStr += String.format(" (\"%s\"~2)^1.5", tokenizer.tokenize(tt));
            }

            List<String> synQuoted = new ArrayList<>();
            for (String s : synsTt) {
                synQuoted.add("\"" + tokenizer.tokenize(subSpecialChar(s)) + "\"");
            }
            String synsJoined = String.join(" OR ", synQuoted);
            if (!synsJoined.isEmpty() && !tmsStr.isEmpty()) {
                tmsStr = String.format("(%s)^5 OR (%s)^0.7", tmsStr, synsJoined);
            }

            qs.add(tmsStr);
        }

        if (!qs.isEmpty()) {
            List<String> wrapped = new ArrayList<>();
            for (String t : qs) {
                if (t != null && !t.isEmpty()) {
                    wrapped.add("(" + t + ")");
                }
            }
            String query = String.join(" OR ", wrapped);
            if (query.isEmpty()) {
                query = otxt;
            }
            Map<String, Object> extra = new HashMap<>();
            extra.put(MatchTextExpr.KEY_MINIMUM_SHOULD_MATCH, minMatch);
            extra.put(MatchTextExpr.KEY_ORIGINAL_QUERY, originalQuery);
            return new QuestionResult(new MatchTextExpr(queryFields, query, 100, extra), keywords);
        }
        return new QuestionResult(null, keywords);
    }

    /**
     * 格式化权重（Python f"{w}"，尽量还原其字符串表现）。
     */
    private String formatNum(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) {
            return String.valueOf((long) w);
        }
        return String.valueOf(w);
    }

    // ==================== rerank 相似度（对齐 Python FulltextQueryer） ====================

    /**
     * Term 相似度（百分百还原 Python {@code FulltextQueryer.token_similarity}）。
     *
     * <p>将查询关键词与每个候选 chunk 的 token 列表分别转成「加权词/bigram 词典」后，
     * 逐个计算 {@link #similarity} 得分，供 {@code Dealer.rerank_with_knn} 与外部
     * rerank 融合。对应 Python：{@code return [self.similarity(atks, btks) for btks in btkss]}。</p>
     *
     * @param atks  查询关键词列表（对应 keywords）
     * @param btkss 每个候选 chunk 的 token 列表（对应 ins_tw）
     * @return 与每个候选的 term 相似度分数列表
     */
    public List<Double> tokenSimilarity(List<String> atks, List<List<String>> btkss) {
        Map<String, Double> aDict = toWeightDict(atks);
        List<Double> res = new ArrayList<>(btkss.size());
        for (List<String> tks : btkss) {
            res.add(similarity(aDict, toWeightDict(tks)));
        }
        return res;
    }

    /**
     * 把 token 列表转成加权词典（对应 Python token_similarity 内部的 to_dict）。
     *
     * <p>规则：{@code d[t] += c*0.4}；若存在下一个词，则 {@code d[t+_t] += max(c,_c)*0.6}。
     * 其中 (t,c) 来自 {@code tw.weights(tks, preprocess=False)}。</p>
     */
    private Map<String, Double> toWeightDict(List<String> tks) {
        Map<String, Double> d = new HashMap<>();
        List<TermWeight.TW> wts = tw.weights(new ArrayList<>(tks), false);
        for (int i = 0; i < wts.size(); i++) {
            String t = wts.get(i).token;
            double c = wts.get(i).weight;
            d.merge(t, c * 0.4, Double::sum);
            if (i + 1 < wts.size()) {
                String t2 = wts.get(i + 1).token;
                double c2 = wts.get(i + 1).weight;
                d.merge(t + t2, Math.max(c, c2) * 0.6, Double::sum);
            }
        }
        return d;
    }

    /**
     * 加权词典余弦式相似度（百分百还原 Python {@code FulltextQueryer.similarity}）。
     *
     * <p>对应 Python：{@code s = sum(v for k,v in qtwt if k in dtwt) + 1e-9;
     * q = sum(qtwt.values()) + 1e-9; return s / q}。</p>
     */
    public double similarity(Map<String, Double> qtwt, Map<String, Double> dtwt) {
        double s = 1e-9;
        for (Map.Entry<String, Double> e : qtwt.entrySet()) {
            if (dtwt.containsKey(e.getKey())) {
                s += e.getValue();
            }
        }
        double q = 1e-9;
        for (Double v : qtwt.values()) {
            q += v;
        }
        return s / q;
    }
}