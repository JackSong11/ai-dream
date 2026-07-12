package com.example.dream.web.vo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 通用删除请求视图对象，对应 RagFlow DELETE 请求体 {ids, delete_all}。
 *
 * @author dream
 */
@Data
public class DeleteReqVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 待删除 ID 列表（字符串，前端传入）
     */
    private List<String> ids;

    /**
     * 是否删除全部
     */
    private Boolean deleteAll;
}