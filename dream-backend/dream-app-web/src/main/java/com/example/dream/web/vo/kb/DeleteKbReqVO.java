package com.example.dream.web.vo.kb;

import lombok.Data;

import java.util.List;

/**
 * 删除知识库请求视图对象，对应 RagFlow DELETE /datasets 请求体。
 */
@Data
public class DeleteKbReqVO {

    /**
     * 待删除知识库 ID 列表；为 null 且 deleteAll=true 时删除全部（对应 RagFlow ids）
     */
    private List<Long> ids;

    /**
     * 是否删除该用户全部知识库（对应 RagFlow delete_all）
     */
    private Boolean deleteAll = false;
}