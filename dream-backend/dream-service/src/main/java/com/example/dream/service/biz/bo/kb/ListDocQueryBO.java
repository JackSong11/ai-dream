package com.example.dream.service.biz.bo.kb;

import lombok.Data;

import java.util.List;

/**
 * 文档列表查询参数业务对象，对应 RagFlow document_api._get_docs_with_request 提取的查询参数。
 */
@Data
public class ListDocQueryBO {

    /**
     * 所属知识库 ID（对应 RagFlow dataset_id / kb_id）
     */
    private Long kbId;

    /**
     * 页码，默认 1（对应 RagFlow page）
     */
    private Integer page = 1;

    /**
     * 每页大小，默认 30（对应 RagFlow page_size）
     */
    private Integer pageSize = 30;

    /**
     * 排序字段，默认 create_time（对应 RagFlow orderby，映射到 created_time）
     */
    private String orderby = "create_time";

    /**
     * 是否降序，默认 true（对应 RagFlow desc）
     */
    private Boolean desc = true;

    /**
     * 名称关键字模糊匹配（对应 RagFlow keywords）
     */
    private String keywords;

    /**
     * 文件后缀过滤，如 ["pdf","txt"]（对应 RagFlow suffix）
     */
    private List<String> suffix;

    /**
     * 文档类型过滤（对应 RagFlow types）
     */
    private List<String> types;

    /**
     * 运行状态过滤，数值形式 0/1/2/3/4（对应 RagFlow run）
     */
    private List<Integer> run;
}