package com.example.dream.web.vo.kb;

import lombok.Data;

import java.util.List;

/**
 * 文档列表分页视图对象，对应 RagFlow list_docs 返回 {"total": n, "docs": [...]}。
 */
@Data
public class DocListVO {

    /**
     * 总记录数
     */
    private long total;

    /**
     * 当前页文档列表
     */
    private List<DocItemVO> docs;
}