package com.example.dream.service.core.chat.retriever.nlp;

import java.util.HashMap;
import java.util.Map;

/**
 * huqie 词典 trie（对应 RagFlow infinity.rag_tokenizer 中的 {@code datrie.Trie}）。
 *
 * <p>RagFlow 用 datrie 存两类 key：
 * <ul>
 *   <li>正向 key（{@code key_}）：{@code str(line.lower().encode("utf-8"))[2:-1]}，
 *       其 value 为 {@code (F, tag)}，F 为 {@code round(log(freq/DENOMINATOR))}；</li>
 *   <li>反向 key（{@code rkey_}）：{@code str(("DD"+reversed_lower).encode("utf-8"))[2:-1]}，value 恒为 1，
 *       仅用于反向最大匹配时的 {@code has_keys_with_prefix} 前缀判定。</li>
 * </ul>
 * 本类以 Java 原生字符串直接作为 key（无需还原 Python 的 bytes-repr 编码，因为编码只是 datrie 的内部
 * 存储细节，对「是否命中」「前缀是否存在」的语义无影响），value 用 {@link Entry} 承载 (F, tag)。
 * 前缀判定 {@link #hasKeysWithPrefix} 用一个 {@link java.util.HashMap} 记录所有前缀的存在性，
 * 等价于 datrie 的 {@code has_keys_with_prefix}。</p>
 *
 * @author dream
 */
public final class HuqieTrie {

    /** 词典项：F=round(log(freq/DENOMINATOR))，tag=词性。 */
    public static final class Entry {
        public final int f;
        public final String tag;

        public Entry(int f, String tag) {
            this.f = f;
            this.tag = tag;
        }
    }

    /** DENOMINATOR，对应 RagFlow self.DENOMINATOR = 1000000。 */
    public static final long DENOMINATOR = 1_000_000L;

    /** 正向 key -> (F, tag)。 */
    private final Map<String, Entry> forward = new HashMap<>(1 << 20);

    /**
     * 所有正向 key 的前缀集合（含 key 自身），用于 {@link #hasKeysWithPrefix}。
     * 对应 datrie 对正向 key 的 has_keys_with_prefix。
     */
    private final Map<String, Boolean> forwardPrefix = new HashMap<>(1 << 22);

    /**
     * 所有反向 key 的前缀集合（含 key 自身），用于反向最大匹配的前缀判定。
     * 对应 datrie 对 rkey_ 的 has_keys_with_prefix。
     */
    private final Map<String, Boolean> backwardPrefix = new HashMap<>(1 << 22);

    /**
     * 正向 key（对应 RagFlow key_）：这里直接用小写原串（语义等价）。
     */
    public String key(String line) {
        return line.toLowerCase();
    }

    /**
     * 反向 key（对应 RagFlow rkey_ = "DD" + reversed(lower)）：用于反向匹配前缀判定。
     */
    public String rkey(String line) {
        StringBuilder sb = new StringBuilder("DD");
        String low = line.toLowerCase();
        for (int i = low.length() - 1; i >= 0; i--) {
            sb.append(low.charAt(i));
        }
        return sb.toString();
    }

    /**
     * 加入一条词典项（对应 _load_dict：F=round(log(freq/DENOMINATOR))，保留 F 更大的那条）。
     */
    public void put(String word, int f, String tag) {
        String k = key(word);
        Entry old = forward.get(k);
        if (old == null || old.f < f) {
            forward.put(k, new Entry(f, tag));
        }
        addPrefixes(forwardPrefix, k);
        addPrefixes(backwardPrefix, rkey(word));
    }

    private void addPrefixes(Map<String, Boolean> set, String k) {
        for (int i = 1; i <= k.length(); i++) {
            set.putIfAbsent(k.substring(0, i), Boolean.TRUE);
        }
    }

    /** 正向 key 是否命中（对应 {@code k in self.trie_}）。 */
    public boolean contains(String k) {
        return forward.containsKey(k);
    }

    /** 取正向 key 对应的 (F, tag)（对应 {@code self.trie_[k]}）。 */
    public Entry get(String k) {
        return forward.get(k);
    }

    /** 正向前缀是否存在（对应正向 key 的 {@code has_keys_with_prefix}）。 */
    public boolean hasKeysWithPrefix(String prefix) {
        return forwardPrefix.containsKey(prefix);
    }

    /** 反向前缀是否存在（对应 rkey_ 的 {@code has_keys_with_prefix}）。 */
    public boolean hasKeysWithPrefixBackward(String rprefix) {
        return backwardPrefix.containsKey(rprefix);
    }

    public int size() {
        return forward.size();
    }
}