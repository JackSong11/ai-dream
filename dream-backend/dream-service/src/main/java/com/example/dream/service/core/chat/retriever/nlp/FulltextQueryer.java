package com.example.dream.service.core.chat.retriever.nlp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 全文查询构建器（百分百还原 RagFlow {@code rag/nlp/query.py} 的 {@code FulltextQueryer}，
 * 含其基类 {@code common/query_base.py} 的 {@code QueryBase} 工具方法）。
 *
 * <p>{@link #question} 将用户 query 转成结构化全文匹配表达式（{@link MatchTextExpr}）并返回关键词
 * {@code keywords}。处理流程：中英文加空格 -> 特殊字符清洗/繁简/全半角/小写 -> {@code rmWWW} 去疑问停用词
 * -> 中/英分支：分词（{@link RagTokenizer}）+ 词权重（{@link TermWeight}）+ 同义词（{@link Synonym}）+
 * 细粒度分词 -> 组装带 boost/邻近({@code ~2})/{@code minimum_should_match} 的表达式。</p>
 *
 * @author dream
 */
public final class FulltextQueryer {

    // ===== QueryBase 相关正则 =====
    private static final Pattern SPLIT_WS = Pattern.compile("[ \\t]+");
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

    /** query_fields，与 RagFlow 完全一致。 */
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

    /** 对应 QueryBase.is_chinese。 */
    static boolean isChinese(String line) {
        String[] arr = SPLIT_WS.split(line);
        if (arr.length <= 3) {
            return true;
        }
        int e = 0;
        for (String t : arr) {
            if (!ALPHA_ONLY.matcher(t).matches()) {
                e++;
            }
        }
        return e * 1.0 / arr.length >= 0.7;
    }

    /** 对应 QueryBase.sub_special_char。 */
    static String subSpecialChar(String line) {
        String noQuote = line.replace("'", "");
        return SUB_SPECIAL.matcher(noQuote).replaceAll("\\\\$1").trim();
    }

    /** 对应 QueryBase.rmWWW。 */
    static String rmWWW(String txt) {
        String otxt = txt;
        txt = RM_ZH.matcher(txt).replaceAll("");
        txt = RM_EN1.matcher(txt).replaceAll(" ");
        txt = RM_EN2.matcher(txt).replaceAll(" ");
        if (txt.isEmpty()) {
            txt = otxt;
        }
        return txt;
    }

    /** 对应 QueryBase.add_space_between_eng_zh。 */
    static String addSpaceBetweenEngZh(String txt) {
        txt = txt.replaceAll("([A-Za-z]+[0-9]+)([\\u4e00-\\u9fa5]+)", "$1 $2");
        txt = txt.replaceAll("([A-Za-z])([\\u4e00-\\u9fa5]+)", "$1 $2");
        txt = txt.replaceAll("([\\u4e00-\\u9fa5]+)([A-Za-z]+[0-9]+)", "$1 $2");
        txt = txt.replaceAll("([\\u4e00-\\u9fa5]+)([A-Za-z])", "$1 $2");
        return txt;
    }

    // ==================== question ====================

    /** question 的返回：MatchTextExpr（可能为 null）+ keywords。 */
    public static final class QuestionResult {
        public final MatchTextExpr matchExpr;
        public final List<String> keywords;

        public QuestionResult(MatchTextExpr matchExpr, List<String> keywords) {
            this.matchExpr = matchExpr;
            this.keywords = keywords;
        }
    }

    public QuestionResult question(String txt) {
        return question(txt, 0.6);
    }

    /**
     * 构建全文查询（百分百还原 FulltextQueryer.question）。
     */
    public QuestionResult question(String txt, double minMatch) {
        String originalQuery = txt;
        txt = addSpaceBetweenEngZh(txt);

        // 清洗 Infinity ESCAPABLE 字符：tradi2simp(strQ2B(lower)) 后按正则替换为空格
        txt = ESCAPABLE.matcher(tokenizer.tradi2simp(tokenizer.strQ2B(txt.toLowerCase())))
                .replaceAll(" ").trim();
        String otxt = txt;
        txt = rmWWW(txt);

        if (!isChinese(txt)) {
            // ===== 非中文分支 =====
            txt = rmWWW(txt);
            String[] tks = tokenizer.tokenize(txt).trim().isEmpty()
                    ? new String[0] : tokenizer.tokenize(txt).trim().split("\\s+");
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
            extra.put("original_query", originalQuery);
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

    /** question 的中文分支（对应 Python 中 is_chinese 为真的后半段）。 */
    private QuestionResult questionChinese(String txt, String otxt, String originalQuery, double minMatch) {
        txt = rmWWW(txt);
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
            extra.put("minimum_should_match", minMatch);
            extra.put("original_query", originalQuery);
            return new QuestionResult(new MatchTextExpr(queryFields, query, 100, extra), keywords);
        }
        return new QuestionResult(null, keywords);
    }

    /** 格式化权重（Python f"{w}"，尽量还原其字符串表现）。 */
    private String formatNum(double w) {
        if (w == Math.rint(w) && !Double.isInfinite(w)) {
            return String.valueOf((long) w);
        }
        return String.valueOf(w);
    }
}