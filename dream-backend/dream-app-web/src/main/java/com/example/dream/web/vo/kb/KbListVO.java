package com.example.dream.web.vo.kb;

import lombok.Data;

import java.util.List;

/**
 * 知识库列表分页视图对象，对应 RagFlow list_datasets 返回 {"data": [...], "total": n}。
 */
@Data
public class KbListVO {

    /**
     * 当前页知识库列表
     */
    private List<KnowledgeBaseVO> data;

    /**
     * 总记录数
     */
    private long total;
}