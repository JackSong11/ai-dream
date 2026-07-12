package com.example.dream.service.biz.bo.kb;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档过滤聚合结果业务对象，对应 RagFlow DocumentService.get_filter_by_kb_id 返回结构。
 *
 * <pre>
 * {
 *   "suffix": {"pdf": 1, "docx": 2},
 *   "run_status": {"0": 2, "3": 1},
 *   "metadata": {"empty_metadata": {"true": n}}
 * }
 * </pre>
 *
 * <p>本项目暂不支持 ES/Infinity 复杂 metadata 聚合，metadata 仅返回 empty_metadata 计数，
 * 与 RagFlow 在无 metadata 时的输出保持一致。</p>
 */
@Data
public class DocFilterBO {

    /**
     * 按后缀聚合计数（对应 RagFlow suffix_counter）
     */
    private Map<String, Integer> suffix = new LinkedHashMap<>();

    /**
     * 按运行状态聚合计数，键为状态数值字符串（对应 RagFlow run_status_counter）
     */
    private Map<String, Integer> runStatus = new LinkedHashMap<>();

    /**
     * metadata 聚合计数（对应 RagFlow metadata_counter），本项目仅含 empty_metadata。
     */
    private Map<String, Map<String, Integer>> metadata = new LinkedHashMap<>();
}