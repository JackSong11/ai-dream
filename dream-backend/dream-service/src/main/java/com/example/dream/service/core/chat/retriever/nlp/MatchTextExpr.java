package com.example.dream.service.core.chat.retriever.nlp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全文匹配表达式（对应 RagFlow {@code common/doc_store/doc_store_base.py} 的 {@code MatchTextExpr}）。
 *
 * <p>由 {@link FulltextQueryer#question} 生成，承载：
 * <ul>
 *   <li>{@code fields}：待匹配字段（含 boost，如 {@code title_tks^10}）；</li>
 *   <li>{@code matchingText}：构建好的结构化 query 表达式（Lucene/Infinity 语法）；</li>
 *   <li>{@code topn}：匹配上限；</li>
 *   <li>{@code extraOptions}：如 {@code minimum_should_match}、{@code original_query}。</li>
 * </ul>
 * ES 侧可据此构建 query_string（{@code matchingText}）与 {@code minimum_should_match}。</p>
 *
 * @author dream
 */
public final class MatchTextExpr {

    private final List<String> fields;
    private final String matchingText;
    private final int topn;
    private final Map<String, Object> extraOptions;

    public MatchTextExpr(List<String> fields, String matchingText, int topn,
                         Map<String, Object> extraOptions) {
        this.fields = fields;
        this.matchingText = matchingText;
        this.topn = topn;
        this.extraOptions = extraOptions == null ? new HashMap<>() : extraOptions;
    }

    public List<String> getFields() {
        return fields;
    }

    public String getMatchingText() {
        return matchingText;
    }

    public int getTopn() {
        return topn;
    }

    public Map<String, Object> getExtraOptions() {
        return extraOptions;
    }

    /** 便捷取 minimum_should_match（可能为百分比 double 或字符串）。 */
    public Object getMinimumShouldMatch() {
        return extraOptions.get("minimum_should_match");
    }

    public String getOriginalQuery() {
        Object v = extraOptions.get("original_query");
        return v == null ? null : String.valueOf(v);
    }
}