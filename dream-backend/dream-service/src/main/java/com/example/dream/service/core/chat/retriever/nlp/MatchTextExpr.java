package com.example.dream.service.core.chat.retriever.nlp;

import lombok.Getter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 全文匹配表达式（对应 RagFlow {@code common/doc_store/doc_store_base.py} 的 {@code MatchTextExpr}）。
 *
 * <p>由 {FulltextQueryer#question} 生成，承载：
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
@Getter
public final class MatchTextExpr {

    /** extraOptions 中 minimum_should_match 的 key。 */
    public static final String KEY_MINIMUM_SHOULD_MATCH = "minimum_should_match";

    /** extraOptions 中 original_query 的 key。 */
    public static final String KEY_ORIGINAL_QUERY = "original_query";

    /**
     * 待匹配字段列表（含 boost，如 {@code title_tks^10}）。
     */
    private final List<String> fields;

    /**
     * 构建好的结构化 query 表达式（Lucene/Infinity 语法）。
     */
    private final String matchingText;

    /**
     * 匹配上限。
     */
    private final int topn;

    /**
     * 附加选项（如 {@code minimum_should_match}、{@code original_query}）。
     */
    private final Map<String, Object> extraOptions;

    public MatchTextExpr(List<String> fields, String matchingText, int topn,
                         Map<String, Object> extraOptions) {
        this.fields = fields;
        this.matchingText = matchingText;
        this.topn = topn;
        this.extraOptions = extraOptions == null ? Collections.emptyMap() : extraOptions;
    }

    /** 便捷取 minimum_should_match（可能为百分比 double 或字符串）。 */
    public Object getMinimumShouldMatch() {
        return extraOptions.get(KEY_MINIMUM_SHOULD_MATCH);
    }

    /** 便捷取 original_query。 */
    public String getOriginalQuery() {
        Object v = extraOptions.get(KEY_ORIGINAL_QUERY);
        return v == null ? null : String.valueOf(v);
    }
}