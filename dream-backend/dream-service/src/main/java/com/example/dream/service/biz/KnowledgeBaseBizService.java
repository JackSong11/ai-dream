package com.example.dream.service.biz;

import com.example.dream.service.biz.bo.PageResultBO;
import com.example.dream.service.biz.bo.kb.CreateKbBO;
import com.example.dream.service.biz.bo.kb.DocFilterBO;
import com.example.dream.service.biz.bo.kb.DocItemBO;
import com.example.dream.service.biz.bo.kb.KnowledgeBaseBO;
import com.example.dream.service.biz.bo.kb.ListDocQueryBO;
import com.example.dream.service.biz.bo.kb.UpdateKbBO;

import java.util.List;

/**
 * 知识库（dataset）业务编排服务。
 *
 * <p>对应 RagFlow dataset_api.py + dataset_api_service.py 与 document_api.py 中的
 * 知识库增删改查、文档列表核心逻辑。鉴权使用当前登录用户 userId（替代 RagFlow tenant_id）。</p>
 */
public interface KnowledgeBaseBizService {

    /**
     * 创建知识库，对应 RagFlow create_dataset。
     *
     * @param create 创建请求
     * @param userId 当前登录用户 ID
     * @return 创建成功的知识库
     */
    KnowledgeBaseBO createDataset(CreateKbBO create, String userId);

    /**
     * 分页查询知识库列表，对应 RagFlow list_datasets。
     *
     * @param name     名称精确过滤（可空）
     * @param keywords 名称关键字模糊过滤（可空）
     * @param page     页码
     * @param pageSize 每页大小
     * @param orderby  排序字段（create_time / update_time）
     * @param desc     是否降序
     * @param userId   当前登录用户 ID
     * @return 分页结果
     */
    PageResultBO<KnowledgeBaseBO> listDatasets(String name, String keywords, int page, int pageSize,
                                               String orderby, boolean desc, String userId);

    /**
     * 查询知识库详情，对应 RagFlow get_dataset。
     *
     * @param datasetId 知识库 ID
     * @param userId    当前登录用户 ID
     * @return 知识库详情
     */
    KnowledgeBaseBO getDataset(Long datasetId, String userId);

    /**
     * 更新知识库，对应 RagFlow update_dataset。
     *
     * @param datasetId 知识库 ID
     * @param update    更新请求（字段为 null 表示不更新）
     * @param userId    当前登录用户 ID
     * @return 更新后的知识库
     */
    KnowledgeBaseBO updateDataset(Long datasetId, UpdateKbBO update, String userId);

    /**
     * 删除知识库（含其文档、存储对象、任务、ES 数据），对应 RagFlow delete_datasets。
     *
     * @param ids       待删除 ID 列表
     * @param deleteAll ids 为空时是否删除当前用户全部知识库
     * @param userId    当前登录用户 ID
     * @return 成功删除数量
     */
    int deleteDatasets(List<Long> ids, boolean deleteAll, String userId);

    /**
     * 分页查询知识库下文档列表，对应 RagFlow list_docs / get_by_kb_id。
     *
     * @param query  查询参数
     * @param userId 当前登录用户 ID
     * @return 分页结果
     */
    PageResultBO<DocItemBO> listDocs(ListDocQueryBO query, String userId);

    /**
     * 查询知识库下文档的过滤聚合信息，对应 RagFlow list_docs 的 type=filter 分支
     * （DocumentService.get_filter_by_kb_id）。
     *
     * <p>按 keywords / suffix / types / run 条件过滤后，聚合出各后缀、各运行状态的文档数量，
     * 用于前端过滤面板展示每个筛选项的可选数量。</p>
     *
     * @param query  查询参数（复用文档列表参数，忽略分页与排序）
     * @param userId 当前登录用户 ID
     * @return 聚合结果与命中总数
     */
    PageResultBO<DocFilterBO> getDocFilters(ListDocQueryBO query, String userId);
}