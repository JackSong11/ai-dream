package com.example.dream.service.core.chat.retriever.nlp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 同义词扩展（百分百还原 RagFlow {@code rag/nlp/synonym.py} 的 {@code Dealer}）。
 *
 * <p>{@code lookup}：先查自定义词典 {@code synonym.json}（key 已小写化）；未命中且为纯字母时，
 * RagFlow 会回退 WordNet。Java 侧无内置 WordNet 词库，回退分支返回空（等价于 WordNet 无命中），
 * 不影响主流程（自定义词典命中优先）。实时 Redis 同义词（{@code kevin_synonyms}）本项目未接入。</p>
 *
 * @author dream
 */
@Slf4j
public final class Synonym {

    private static final Pattern TAB_SPACE = Pattern.compile("[ \\t]+");
    private static final Pattern PURE_ALPHA = Pattern.compile("[a-z]+");

    /** 自定义同义词词典（对应 self.dictionary，key 小写）。 */
    private final Map<String, List<String>> dictionary = new LinkedHashMap<>();

    private static volatile Synonym instance;

    public static Synonym getInstance() {
        if (instance == null) {
            synchronized (Synonym.class) {
                if (instance == null) {
                    instance = new Synonym();
                }
            }
        }
        return instance;
    }

    private Synonym() {
        load();
    }

    private void load() {
        try (InputStream in = new ClassPathResource("nlp/synonym.json").getInputStream()) {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(in);
            Iterator<Map.Entry<String, JsonNode>> it = root.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey() == null ? null : e.getKey().toLowerCase();
                if (key == null) {
                    continue;
                }
                List<String> vals = new ArrayList<>();
                JsonNode v = e.getValue();
                if (v.isArray()) {
                    for (JsonNode n : v) {
                        vals.add(n.asText());
                    }
                } else {
                    // 对应 Python：字符串 value 也当作 [value]
                    vals.add(v.asText());
                }
                dictionary.put(key, vals);
            }
            log.info("[SYN] load synonym.json size={}", dictionary.size());
        } catch (Exception e) {
            log.warn("Missing synonym.json", e);
        }
        if (dictionary.isEmpty()) {
            log.warn("Fail to load synonym");
        }
    }

    /** 默认 topn=8 的 lookup。 */
    public List<String> lookup(String tk) {
        return lookup(tk, 8);
    }

    /**
     * 同义词查找（对应 lookup）。
     */
    public List<String> lookup(String tk, int topn) {
        if (tk == null || tk.isEmpty()) {
            return new ArrayList<>();
        }
        // 1) 自定义词典（key 与 tk 均小写）
        String key = TAB_SPACE.matcher(tk.trim()).replaceAll(" ");
        List<String> res = dictionary.get(key.toLowerCase());
        if (res != null && !res.isEmpty()) {
            return res.size() > topn ? new ArrayList<>(res.subList(0, topn)) : new ArrayList<>(res);
        }

        // 2) 纯字母回退 WordNet：Java 侧无内置 WordNet，返回空（等价于无命中）
        if (PURE_ALPHA.matcher(tk).matches()) {
            return new ArrayList<>();
        }

        // 3) 都没有
        return new ArrayList<>();
    }
}