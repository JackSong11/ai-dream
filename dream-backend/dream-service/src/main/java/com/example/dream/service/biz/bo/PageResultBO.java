package com.example.dream.service.biz.bo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页结果业务对象，对应 RagFlow service 返回的 {"data": [...], "total": n} 结构。
 *
 * @param <T> 列表元素类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResultBO<T> {

    /**
     * 当前页数据列表
     */
    private List<T> data;

    /**
     * 符合条件的总记录数
     */
    private long total;
}