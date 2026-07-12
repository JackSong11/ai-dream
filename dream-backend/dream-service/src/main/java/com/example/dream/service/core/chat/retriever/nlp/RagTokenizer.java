package com.example.dream.service.core.chat.retriever.nlp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 中文/英文混合分词器（百分百还原 RagFlow {@code infinity.rag_tokenizer.RagTokenizer}）。
 *
 * <p>算法：基于 huqie 词典的正向/反向最大匹配（{@code _max_forward}/{@code _max_backward}），
 * 对两者分歧片段用 DFS（{@code dfs_}）枚举全部切分并按 {@code score_} 打分取最优，最后 {@code merge_}
 * 合并可成词的相邻片段。英文走 {@code word_tokenize}+归一化，纯数字/纯字母/短串直接保留。</p>
 *
 * <p>提供 {@code tokenize}、{@code fineGrainedTokenize}、{@code freq}、{@code tag}、{@code strQ2B}、
 * {@code tradi2simp}，供 {@link TermWeight}、{@link FulltextQueryer} 使用。</p>
 *
 * @author dream
 */
@Slf4j
public final class RagTokenizer {

    /** SPLIT_CHAR，与 RagFlow 完全一致。 */
    private static final Pattern SPLIT_CHAR = Pattern.compile(
            "([ ,\\.<>/?;:'\\[\\]\\\\`!@#$%^&*\\(\\)\\{\\}\\|_+=《》，。？、；‘’：“”【】~！￥%……（）——-]+|[a-zA-Z0-9,\\.-]+)");

    private static final Pattern NON_WORD = Pattern.compile("\\W+");
    private static final Pattern EN_OR_NUM = Pattern.compile("[a-z\\.-]+$");
    private static final Pattern NUM_ONLY = Pattern.compile("[0-9\\.-]+$");
    private static final Pattern NUM_COMMA_DOT = Pattern.compile("[0-9,\\.-]+$");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ ]+");
    private static final Pattern EN_TAIL = Pattern.compile(".*[a-zA-Z]$");

    private final HuqieTrie trie = new HuqieTrie();

    /** 繁->简 单字符映射（来自 hanziconv charmap 导出的 t2s.txt）。 */
    private final Map<Character, Character> t2s = new HashMap<>(4096);

    private static volatile RagTokenizer instance;

    /** 全局单例（词典较大，只加载一次）。 */
    public static RagTokenizer getInstance() {
        if (instance == null) {
            synchronized (RagTokenizer.class) {
                if (instance == null) {
                    instance = new RagTokenizer();
                }
            }
        }
        return instance;
    }

    private RagTokenizer() {
        loadTradiSimp();
        loadDict();
    }

    private void loadTradiSimp() {
        try (InputStream in = new ClassPathResource("nlp/t2s.txt").getInputStream()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (int i = 0; i + 1 < s.length(); i += 2) {
                t2s.put(s.charAt(i), s.charAt(i + 1));
            }
            log.info("[HUQIE] load t2s map size={}", t2s.size());
        } catch (Exception e) {
            log.warn("[HUQIE] load t2s.txt failed, tradi2simp disabled", e);
        }
    }

    /**
     * 加载 huqie 词典（对应 _load_dict）：每行 {@code 词 频次 词性}，
     * F = round(log(freq/DENOMINATOR))。
     */
    private void loadDict() {
        long start = System.currentTimeMillis();
        try (InputStream in = new ClassPathResource("nlp/huqie.txt").getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.replaceAll("[\\r\\n]+", "");
                if (line.isEmpty()) {
                    continue;
                }
                String[] arr = line.split("[ \\t]");
                if (arr.length < 3) {
                    continue;
                }
                try {
                    double freq = Double.parseDouble(arr[1]);
                    int f = (int) Math.floor(Math.log(freq / HuqieTrie.DENOMINATOR) + 0.5);
                    trie.put(arr[0], f, arr[2]);
                } catch (NumberFormatException ignore) {
                    // 跳过非法行
                }
            }
            log.info("[HUQIE] load huqie.txt entries={}, cost={}ms",
                    trie.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[HUQIE] load huqie.txt failed", e);
        }
    }

    // ==================== 基础字符处理 ====================

    /** 全角转半角（对应 _strQ2B）。 */
    public String strQ2B(String ustring) {
        StringBuilder sb = new StringBuilder(ustring.length());
        for (int i = 0; i < ustring.length(); i++) {
            int code = ustring.charAt(i);
            if (code == 0x3000) {
                code = 0x0020;
            } else {
                code -= 0xFEE0;
            }
            if (code < 0x0020 || code > 0x7E) {
                sb.append(ustring.charAt(i));
            } else {
                sb.append((char) code);
            }
        }
        return sb.toString();
    }

    /** 繁体转简体（对应 _tradi2simp，用 hanziconv 导出的映射表）。 */
    public String tradi2simp(String line) {
        if (t2s.isEmpty()) {
            return line;
        }
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            Character mapped = t2s.get(c);
            sb.append(mapped == null ? c : mapped);
        }
        return sb.toString();
    }

    public static boolean isChinese(char s) {
        return s >= '\u4e00' && s <= '\u9fa5';
    }

    /** 词频（对应 freq）：round(exp(F) * DENOMINATOR)。 */
    public int freq(String tk) {
        HuqieTrie.Entry e = trie.get(trie.key(tk));
        if (e == null) {
            return 0;
        }
        return (int) Math.floor(Math.exp(e.f) * HuqieTrie.DENOMINATOR + 0.5);
    }

    /** 词性（对应 tag）。 */
    public String tag(String tk) {
        HuqieTrie.Entry e = trie.get(trie.key(tk));
        return e == null ? "" : e.tag;
    }

    // ==================== 打分 / 合并 ====================

    /** (tokens, score)。 */
    static final class Scored {
        final List<String> tks;
        final double score;

        Scored(List<String> tks, double score) {
            this.tks = tks;
            this.score = score;
        }
    }

    /** (token, freq, tag) 三元组。 */
    static final class TFT {
        final String tk;
        final int freq;
        final String tag;

        TFT(String tk, int freq, String tag) {
            this.tk = tk;
            this.freq = freq;
            this.tag = tag;
        }
    }

    /** 对应 score_：B/len + L + F。 */
    private Scored score(List<TFT> tfts) {
        int b = 30;
        double f = 0;
        double l = 0;
        List<String> tks = new ArrayList<>(tfts.size());
        for (TFT t : tfts) {
            f += t.freq;
            l += t.tk.length() < 2 ? 0 : 1;
            tks.add(t.tk);
        }
        l /= tfts.size();
        return new Scored(tks, (double) b / tfts.size() + l + f);
    }

    /** 对候选切分列表按 score 降序（对应 _sort_tokens）。 */
    private List<Scored> sortTokens(List<List<TFT>> tkslist) {
        List<Scored> res = new ArrayList<>(tkslist.size());
        for (List<TFT> tfts : tkslist) {
            res.add(score(tfts));
        }
        res.sort((a, c) -> Double.compare(c.score, a.score));
        return res;
    }

    /** 合并可成词的相邻片段（对应 merge_）。 */
    private String merge(String tksStr) {
        String[] arr = MULTI_SPACE.matcher(tksStr).replaceAll(" ").trim().split(" ");
        List<String> tks = new ArrayList<>();
        for (String a : arr) {
            if (!a.isEmpty()) {
                tks.add(a);
            }
        }
        List<String> res = new ArrayList<>();
        int s = 0;
        while (s < tks.size()) {
            int e = s + 1;
            for (int ee = s + 2; ee < Math.min(tks.size() + 2, s + 6); ee++) {
                StringBuilder tk = new StringBuilder();
                for (int k = s; k < Math.min(ee, tks.size()); k++) {
                    tk.append(tks.get(k));
                }
                String t = tk.toString();
                if (SPLIT_CHAR.matcher(t).find() && freq(t) > 0) {
                    e = ee;
                }
            }
            StringBuilder tk = new StringBuilder();
            for (int k = s; k < Math.min(e, tks.size()); k++) {
                tk.append(tks.get(k));
            }
            res.add(tk.toString());
            s = e;
        }
        return String.join(" ", res);
    }

    /** 正向最大匹配（对应 _max_forward）。 */
    private Scored maxForward(String line) {
        List<TFT> res = new ArrayList<>();
        int s = 0;
        int n = line.length();
        while (s < n) {
            int e = s + 1;
            String t = line.substring(s, e);
            while (e < n && trie.hasKeysWithPrefix(trie.key(t))) {
                e += 1;
                t = line.substring(s, e);
            }
            while (e - 1 > s && !trie.contains(trie.key(t))) {
                e -= 1;
                t = line.substring(s, e);
            }
            HuqieTrie.Entry en = trie.get(trie.key(t));
            res.add(en != null ? new TFT(t, en.f, en.tag) : new TFT(t, 0, ""));
            s = e;
        }
        return score(res);
    }

    /** 反向最大匹配（对应 _max_backward）。 */
    private Scored maxBackward(String line) {
        List<TFT> res = new ArrayList<>();
        int s = line.length() - 1;
        while (s >= 0) {
            int e = s + 1;
            String t = line.substring(s, e);
            while (s > 0 && trie.hasKeysWithPrefixBackward(trie.rkey(t))) {
                s -= 1;
                t = line.substring(s, e);
            }
            while (s + 1 < e && !trie.contains(trie.key(t))) {
                s += 1;
                t = line.substring(s, e);
            }
            HuqieTrie.Entry en = trie.get(trie.key(t));
            res.add(en != null ? new TFT(t, en.f, en.tag) : new TFT(t, 0, ""));
            s -= 1;
        }
        java.util.Collections.reverse(res);
        return score(res);
    }

    /**
     * DFS 枚举全部切分（对应 dfs_，含重复字符快捷、双字前缀预判、深度上限与 memo）。
     */
    private int dfs(String chars, int s, List<TFT> preTks, List<List<TFT>> tkslist,
                    int depth, Map<String, Integer> memo) {
        final int maxDepth = 10;
        if (depth > maxDepth) {
            if (s < chars.length()) {
                List<TFT> copy = new ArrayList<>(preTks);
                copy.add(new TFT(chars.substring(s), -12, ""));
                tkslist.add(copy);
            }
            return s;
        }
        StringBuilder stateSb = new StringBuilder().append(s).append('|');
        for (TFT t : preTks) {
            stateSb.append(t.tk).append(',');
        }
        String stateKey = stateSb.toString();
        Integer cached = memo.get(stateKey);
        if (cached != null) {
            return cached;
        }

        int res = s;
        int len = chars.length();
        if (s >= len) {
            tkslist.add(preTks);
            memo.put(stateKey, s);
            return s;
        }
        // 重复字符快捷分支
        if (s < len - 4) {
            boolean repetitive = true;
            char c0 = chars.charAt(s);
            for (int i = 1; i < 5; i++) {
                if (s + i >= len || chars.charAt(s + i) != c0) {
                    repetitive = false;
                    break;
                }
            }
            if (repetitive) {
                int end = s;
                while (end < len && chars.charAt(end) == c0) {
                    end++;
                }
                int mid = s + Math.min(10, end - s);
                String t = chars.substring(s, mid);
                List<TFT> copy = new ArrayList<>(preTks);
                HuqieTrie.Entry en = trie.get(trie.key(t));
                copy.add(en != null ? new TFT(t, en.f, en.tag) : new TFT(t, -12, ""));
                int next = dfs(chars, mid, copy, tkslist, depth + 1, memo);
                res = Math.max(res, next);
                memo.put(stateKey, res);
                return res;
            }
        }

        int startE = s + 1;
        if (s + 2 <= len) {
            String t1 = chars.substring(s, s + 1);
            String t2 = chars.substring(s, s + 2);
            if (trie.hasKeysWithPrefix(trie.key(t1)) && !trie.hasKeysWithPrefix(trie.key(t2))) {
                startE = s + 2;
            }
        }
        if (preTks.size() > 2 && preTks.get(preTks.size() - 1).tk.length() == 1
                && preTks.get(preTks.size() - 2).tk.length() == 1
                && preTks.get(preTks.size() - 3).tk.length() == 1) {
            String t1 = preTks.get(preTks.size() - 1).tk + chars.substring(s, s + 1);
            if (trie.hasKeysWithPrefix(trie.key(t1))) {
                startE = s + 2;
            }
        }

        for (int e = startE; e <= len; e++) {
            String t = chars.substring(s, e);
            String k = trie.key(t);
            if (e > s + 1 && !trie.hasKeysWithPrefix(k)) {
                break;
            }
            if (trie.contains(k)) {
                List<TFT> copy = new ArrayList<>(preTks);
                HuqieTrie.Entry en = trie.get(k);
                copy.add(new TFT(t, en.f, en.tag));
                res = Math.max(res, dfs(chars, e, copy, tkslist, depth + 1, memo));
            }
        }

        if (res > s) {
            memo.put(stateKey, res);
            return res;
        }

        String t = chars.substring(s, s + 1);
        String k = trie.key(t);
        List<TFT> copy = new ArrayList<>(preTks);
        HuqieTrie.Entry en = trie.get(k);
        copy.add(en != null ? new TFT(t, en.f, en.tag) : new TFT(t, -12, ""));
        int result = dfs(chars, s + 1, copy, tkslist, depth + 1, memo);
        memo.put(stateKey, result);
        return result;
    }

    /** 按语言（中/非中）切段（对应 _split_by_lang）。 */
    private List<String[]> splitByLang(String line) {
        List<String[]> pairs = new ArrayList<>();
        String[] arr = SPLIT_CHAR.split(line, -1);
        // Python re.split(捕获组) 会把分隔符也放进结果，这里补回分隔符片段
        List<String> segments = splitKeepDelimiters(line);
        for (String a : segments) {
            if (a == null || a.isEmpty()) {
                continue;
            }
            int s = 0;
            int e = 1;
            boolean zh = isChinese(a.charAt(0));
            while (e < a.length()) {
                boolean cur = isChinese(a.charAt(e));
                if (cur == zh) {
                    e++;
                    continue;
                }
                pairs.add(new String[]{a.substring(s, e), zh ? "1" : "0"});
                s = e;
                e = s + 1;
                zh = cur;
            }
            if (s >= a.length()) {
                continue;
            }
            pairs.add(new String[]{a.substring(s, e), zh ? "1" : "0"});
        }
        return pairs;
    }

    /** 模拟 Python re.split(捕获组)：返回含分隔符片段的序列。 */
    private List<String> splitKeepDelimiters(String line) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = SPLIT_CHAR.matcher(line);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(line.substring(last, m.start()));
            }
            out.add(m.group());
            last = m.end();
        }
        if (last < line.length()) {
            out.add(line.substring(last));
        }
        return out;
    }

    /** 英文分词近似（对应 nltk.word_tokenize：按空白切分，尾部标点拆出）。 */
    private List<String> wordTokenize(String l) {
        List<String> res = new ArrayList<>();
        for (String w : l.trim().split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            int end = w.length();
            while (end > 0 && ".,;:!?\")]}".indexOf(w.charAt(end - 1)) >= 0) {
                end--;
            }
            int begin = 0;
            while (begin < end && "\"([{".indexOf(w.charAt(begin)) >= 0) {
                begin++;
            }
            for (int i = 0; i < begin; i++) {
                res.add(String.valueOf(w.charAt(i)));
            }
            if (begin < end) {
                res.add(w.substring(begin, end));
            }
            for (int i = end; i < w.length(); i++) {
                res.add(String.valueOf(w.charAt(i)));
            }
        }
        return res;
    }

    /** 主分词（对应 tokenize）。 */
    public String tokenize(String line) {
        if (line == null) {
            return "";
        }
        line = NON_WORD.matcher(line).replaceAll(" ");
        line = strQ2B(line).toLowerCase();
        line = tradi2simp(line);

        List<String[]> arr = splitByLang(line);
        List<String> res = new ArrayList<>();
        for (String[] pair : arr) {
            String l = pair[0];
            boolean lang = "1".equals(pair[1]);
            if (!lang) {
                for (String t : wordTokenize(l)) {
                    res.add(EnglishNormalizer.normalize(t));
                }
                continue;
            }
            if (l.length() < 2 || EN_OR_NUM.matcher(l).matches() || NUM_ONLY.matcher(l).matches()) {
                res.add(l);
                continue;
            }

            Scored fw = maxForward(l);
            Scored bw = maxBackward(l);
            List<String> tks = fw.tks;
            List<String> tks1 = bw.tks;

            int i = 0, j = 0, iUnderscore = 0, jUnderscore = 0;
            int same = 0;
            while (i + same < tks1.size() && j + same < tks.size()
                    && tks1.get(i + same).equals(tks.get(j + same))) {
                same++;
            }
            if (same > 0) {
                res.add(String.join(" ", tks.subList(j, j + same)));
            }
            iUnderscore = i + same;
            jUnderscore = j + same;
            j = jUnderscore + 1;
            i = iUnderscore + 1;

            while (i < tks1.size() && j < tks.size()) {
                String tk1 = String.join("", tks1.subList(iUnderscore, i));
                String tk = String.join("", tks.subList(jUnderscore, j));
                if (!tk1.equals(tk)) {
                    if (tk1.length() > tk.length()) {
                        j++;
                    } else {
                        i++;
                    }
                    continue;
                }
                if (!tks1.get(i).equals(tks.get(j))) {
                    i++;
                    j++;
                    continue;
                }
                List<List<TFT>> tkslist = new ArrayList<>();
                String seg = String.join("", tks.subList(jUnderscore, j));
                dfs(seg, 0, new ArrayList<>(), tkslist, 0, new HashMap<>());
                res.add(String.join(" ", sortTokens(tkslist).getFirst().tks));

                same = 1;
                while (i + same < tks1.size() && j + same < tks.size()
                        && tks1.get(i + same).equals(tks.get(j + same))) {
                    same++;
                }
                res.add(String.join(" ", tks.subList(j, j + same)));
                iUnderscore = i + same;
                jUnderscore = j + same;
                j = jUnderscore + 1;
                i = iUnderscore + 1;
            }

            if (iUnderscore < tks1.size()) {
                List<List<TFT>> tkslist = new ArrayList<>();
                String seg = String.join("", tks.subList(jUnderscore, tks.size()));
                dfs(seg, 0, new ArrayList<>(), tkslist, 0, new HashMap<>());
                res.add(String.join(" ", sortTokens(tkslist).get(0).tks));
            }
        }
        return merge(String.join(" ", res));
    }

    /** 细粒度分词（对应 fine_grained_tokenize）。 */
    public String fineGrainedTokenize(String tksStr) {
        String[] tks = tksStr.trim().isEmpty() ? new String[0] : tksStr.trim().split("\\s+");
        int zhNum = 0;
        for (String c : tks) {
            if (!c.isEmpty() && isChinese(c.charAt(0))) {
                zhNum++;
            }
        }
        if (zhNum < tks.length * 0.2) {
            List<String> res = new ArrayList<>();
            for (String tk : tks) {
                for (String p : tk.split("/")) {
                    res.add(p);
                }
            }
            return String.join(" ", res);
        }

        List<String> res = new ArrayList<>();
        for (String tk : tks) {
            if (tk.length() < 3 || NUM_COMMA_DOT.matcher(tk).matches()) {
                res.add(tk);
                continue;
            }
            List<List<TFT>> tkslist = new ArrayList<>();
            if (tk.length() > 10) {
                List<TFT> single = new ArrayList<>();
                single.add(new TFT(tk, 0, ""));
                tkslist.add(single);
            } else {
                dfs(tk, 0, new ArrayList<>(), tkslist, 0, new HashMap<>());
            }
            if (tkslist.size() < 2) {
                res.add(tk);
                continue;
            }
            List<String> stk = sortTokens(tkslist).get(1).tks;
            String out;
            // 对应 Python: if len(stk) == len(tk)（token 数 == 字符数，即全部拆成单字）
            if (stk.size() == tk.length()) {
                out = tk;
            } else {
                if (EN_OR_NUM.matcher(tk).matches()) {
                    boolean shortPart = false;
                    for (String t : stk) {
                        if (t.length() < 3) {
                            shortPart = true;
                            break;
                        }
                    }
                    out = shortPart ? tk : String.join(" ", stk);
                } else {
                    out = String.join(" ", stk);
                }
            }
            res.add(out);
        }
        List<String> normalized = new ArrayList<>(res.size());
        for (String r : res) {
            normalized.add(EnglishNormalizer.normalize(r));
        }
        return String.join(" ", normalized);
    }
}