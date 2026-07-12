package com.example.dream.service.core.chat.retriever.nlp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 词权重计算（百分百还原 RagFlow {@code rag/nlp/term_weight.py} 的 {@code Dealer}）。
 *
 * <p>核心为 {@code weights}：对 token 计算 {@code (0.3*idf(freq)+0.7*idf(df)) * ner(t)*postag(t)}
 * 并归一化。preprocess=True 时先 {@code pretoken}+{@code token_merge}。依赖资源：
 * {@code ner.json}（NER 类型）与 {@code term.freq}（df 词典，缺失时走 fallback，与 RagFlow 当前环境一致）。</p>
 *
 * @author dream
 */
@Slf4j
public final class TermWeight {

    private static final Pattern NUM_PATTERN = Pattern.compile("[0-9,.]{2,}$");
    private static final Pattern SHORT_LETTER = Pattern.compile("[a-z]{1,2}$");
    private static final Pattern NUM_SPACE = Pattern.compile("[0-9. -]{2,}$");
    private static final Pattern LETTER = Pattern.compile("[a-z. -]+$");
    private static final Pattern DIGIT_TAG = Pattern.compile("[0-9-]+.*");
    private static final Pattern TAB_SPACE = Pattern.compile("[ \\t]+");
    private static final Pattern EN_TAIL = Pattern.compile(".*[a-zA-Z]$");
    private static final Pattern ONE_TERM = Pattern.compile("[0-9a-z]{1,2}$");
    private static final Pattern DIGIT_END = Pattern.compile("[0-9]$");
    private static final Pattern PUNCT = Pattern.compile(
            "[~—\\t @#%!<>,\\.\\?\":;'\\{\\}\\[\\]_=\\(\\)\\|，。？》•●○↓《；‘’：“”【¥ 】…￥！、·（）×`&\\\\/「」\\\\]");

    /** 停用词集（对应 self.stop_words）。 */
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "请问", "您", "你", "我", "他", "是", "的", "就", "有", "于", "及", "即", "在", "为",
            "最", "从", "以", "了", "将", "与", "吗", "吧", "中", "#", "什么", "怎么", "哪个",
            "哪些", "啥", "相关"));

    private final RagTokenizer tokenizer;

    /** NER 词典（对应 self.ne，来自 ner.json）。 */
    private final Map<String, String> ne = new HashMap<>();

    /** df 词典（对应 self.df，来自 term.freq；本环境缺失则为空，走 fallback）。 */
    private final Map<String, Integer> df = new HashMap<>();

    private static volatile TermWeight instance;

    public static TermWeight getInstance() {
        if (instance == null) {
            synchronized (TermWeight.class) {
                if (instance == null) {
                    instance = new TermWeight();
                }
            }
        }
        return instance;
    }

    private TermWeight() {
        this.tokenizer = RagTokenizer.getInstance();
        loadNer();
    }

    private void loadNer() {
        try (InputStream in = new ClassPathResource("nlp/ner.json").getInputStream()) {
            ObjectMapper om = new ObjectMapper();
            Map<String, String> m = om.readValue(in, new TypeReference<Map<String, String>>() {
            });
            ne.putAll(m);
            log.info("[TW] load ner.json size={}", ne.size());
        } catch (Exception e) {
            log.warn("Load ner.json FAIL!", e);
        }
        // term.freq 在当前 RagFlow 环境缺失，df 保持为空（与其一致，df() 走 fallback）
    }

    /** 预分词（对应 pretoken）。 */
    List<String> pretoken(String txt, boolean num, boolean stpwd) {
        List<String> res = new ArrayList<>();
        for (String t : tokenizer.tokenize(txt).split("\\s+")) {
            if (t.isEmpty()) {
                continue;
            }
            String tk = t;
            if ((stpwd && STOP_WORDS.contains(tk)) || (DIGIT_END.matcher(tk).matches() && !num)) {
                continue;
            }
            if (PUNCT.matcher(t).lookingAt()) {
                tk = "#";
            }
            if (!tk.equals("#") && !tk.isEmpty()) {
                res.add(tk);
            }
        }
        return res;
    }

    private boolean oneTerm(String t) {
        return t.length() == 1 || ONE_TERM.matcher(t).matches();
    }

    /** token 合并（对应 token_merge）。 */
    List<String> tokenMerge(List<String> tks) {
        List<String> res = new ArrayList<>();
        int i = 0;
        int n = tks.size();
        while (i < n) {
            int j = i;
            if (i == 0 && oneTerm(tks.get(i)) && n > 1
                    && tks.get(i + 1).length() > 1
                    && !Character.toString(tks.get(i + 1).charAt(0)).matches("[0-9a-zA-Z]")) {
                res.add(String.join(" ", tks.subList(0, 2)));
                i = 2;
                continue;
            }
            while (j < n && !tks.get(j).isEmpty()
                    && !STOP_WORDS.contains(tks.get(j)) && oneTerm(tks.get(j))) {
                j++;
            }
            if (j - i > 1) {
                if (j - i < 5) {
                    res.add(String.join(" ", tks.subList(i, j)));
                    i = j;
                } else {
                    res.add(String.join(" ", tks.subList(i, i + 2)));
                    i = i + 2;
                }
            } else {
                if (!tks.get(i).isEmpty()) {
                    res.add(tks.get(i));
                }
                i++;
            }
        }
        List<String> out = new ArrayList<>();
        for (String t : res) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 切分（对应 split）：把相邻纯英文串（非 func）拼接。 */
    public List<String> split(String txt) {
        List<String> tks = new ArrayList<>();
        for (String t : TAB_SPACE.matcher(txt).replaceAll(" ").split(" ")) {
            if (t.isEmpty()) {
                continue;
            }
            if (!tks.isEmpty() && EN_TAIL.matcher(tks.get(tks.size() - 1)).matches()
                    && EN_TAIL.matcher(t).matches()
                    && !"func".equals(ne.getOrDefault(t, ""))
                    && !"func".equals(ne.getOrDefault(tks.get(tks.size() - 1), ""))) {
                tks.set(tks.size() - 1, tks.get(tks.size() - 1) + " " + t);
            } else {
                tks.add(t);
            }
        }
        return tks;
    }

    private double ner(String t) {
        if (NUM_PATTERN.matcher(t).matches()) {
            return 2;
        }
        if (SHORT_LETTER.matcher(t).matches()) {
            return 0.01;
        }
        if (ne.isEmpty() || !ne.containsKey(t)) {
            return 1;
        }
        switch (ne.get(t)) {
            case "toxic":
                return 2;
            case "func":
                return 1;
            case "corp":
            case "loca":
            case "sch":
            case "stock":
                return 3;
            case "firstnm":
                return 1;
            default:
                return 1;
        }
    }

    private double postag(String t) {
        String tg = tokenizer.tag(t);
        if (tg.equals("r") || tg.equals("c") || tg.equals("d")) {
            return 0.3;
        }
        if (tg.equals("ns") || tg.equals("nt")) {
            return 3;
        }
        if (tg.equals("n")) {
            return 2;
        }
        if (DIGIT_TAG.matcher(tg).lookingAt()) {
            return 2;
        }
        return 1;
    }

    private double freqScore(String t) {
        if (NUM_SPACE.matcher(t).matches()) {
            return 3;
        }
        double s = tokenizer.freq(t);
        if (s == 0 && LETTER.matcher(t).matches()) {
            return 300;
        }
        if (s == 0 && t.length() >= 4) {
            List<Double> sub = new ArrayList<>();
            for (String tt : tokenizer.fineGrainedTokenize(t).split("\\s+")) {
                if (tt.length() > 1) {
                    sub.add(freqScore(tt));
                }
            }
            if (sub.size() > 1) {
                double min = Double.MAX_VALUE;
                for (double v : sub) {
                    min = Math.min(min, v);
                }
                s = min / 6.0;
            } else {
                s = 0;
            }
        }
        return Math.max(s, 10);
    }

    private double dfScore(String t) {
        if (NUM_SPACE.matcher(t).matches()) {
            return 5;
        }
        if (df.containsKey(t)) {
            return df.get(t) + 3;
        }
        if (LETTER.matcher(t).matches()) {
            return 300;
        }
        if (t.length() >= 4) {
            List<Double> sub = new ArrayList<>();
            for (String tt : tokenizer.fineGrainedTokenize(t).split("\\s+")) {
                if (tt.length() > 1) {
                    sub.add(dfScore(tt));
                }
            }
            if (sub.size() > 1) {
                double min = Double.MAX_VALUE;
                for (double v : sub) {
                    min = Math.min(min, v);
                }
                return Math.max(3, min / 6.0);
            }
        }
        return 3;
    }

    private double idf(double s, double n) {
        return Math.log10(10 + ((n - s + 0.5) / (s + 0.5)));
    }

    /** (token, weight)。 */
    public static final class TW {
        public final String token;
        public final double weight;

        public TW(String token, double weight) {
            this.token = token;
            this.weight = weight;
        }
    }

    /** 权重计算（对应 weights）。 */
    public List<TW> weights(List<String> tks, boolean preprocess) {
        List<String> keys = new ArrayList<>();
        List<Double> wtsList = new ArrayList<>();
        if (!preprocess) {
            for (String t : tks) {
                double w = (0.3 * idf(freqScore(t), 10000000.0) + 0.7 * idf(dfScore(t), 1000000000.0))
                        * ner(t) * postag(t);
                keys.add(t);
                wtsList.add(w);
            }
        } else {
            for (String tk : tks) {
                List<String> tt = tokenMerge(pretoken(tk, true, true));
                for (String t : tt) {
                    double w = (0.3 * idf(freqScore(t), 10000000.0) + 0.7 * idf(dfScore(t), 1000000000.0))
                            * ner(t) * postag(t);
                    keys.add(t);
                    wtsList.add(w);
                }
            }
        }
        double sum = 0;
        for (double w : wtsList) {
            sum += w;
        }
        List<TW> res = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            res.add(new TW(keys.get(i), sum == 0 ? 0 : wtsList.get(i) / sum));
        }
        return res;
    }
}