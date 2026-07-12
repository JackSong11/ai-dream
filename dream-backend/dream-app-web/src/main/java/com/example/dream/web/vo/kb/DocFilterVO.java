package com.example.dream.web.vo.kb;

import lombok.Data;

import java.util.Map;

/**
 * 文档过滤聚合视图对象，对应 RagFlow list_docs 的 type=filter 返回 {"total": n, "filter": {...}}。
 */
@Data
public class DocFilterVO {

    /**
     * 命中总数（对应 RagFlow total）
     */
    private long total;

    /**
     * 聚合结果（对应 RagFlow filter）
     */
    private FilterBody filter;

    /**
     * 聚合结果体，对应 RagFlow get_filter_by_kb_id 返回结构。
     */
    @Data
    public static class FilterBody {
        /** 按后缀聚合计数 */
        private Map<String, Integer> suffix;
        /** 按运行状态聚合计数（键为状态数值字符串） */
        private Map<String, Integer> runStatus;
        /** metadata 聚合计数（仅含 empty_metadata） */
        private Map<String, Map<String, Integer>> metadata;
    }
}