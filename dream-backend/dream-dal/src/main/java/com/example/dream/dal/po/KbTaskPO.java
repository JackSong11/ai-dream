package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 文档解析任务表持久化对象
 * kb_task
 *
 * <p>对应 RagFlow api.db.db_models.Task，用于记录文档解析任务及进度。
 * 一个文档可拆分为多个 task（按页范围），由异步消费端逐个处理。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true) // 显式声明：包含父类属性
@TableName("kb_task")
public class KbTaskPO extends BasePO {

    /**
     * 所属文档 ID（对应 RagFlow Task.doc_id）
     */
    private Long docId;

    /**
     * 起始页码，从 0 开始（对应 RagFlow Task.from_page）
     */
    private Integer fromPage;

    /**
     * 结束页码，-1 表示到末尾（对应 RagFlow Task.to_page）
     */
    private Integer toPage;

    /**
     * 任务类型（对应 RagFlow task_type，如 common/raptor/graphrag，默认 common）
     */
    private String taskType;

    /**
     * 任务进度，取值 0~1（对应 RagFlow Task.progress）
     */
    private BigDecimal progress;

    /**
     * 进度描述信息（对应 RagFlow Task.progress_msg）
     */
    private String progressMsg;

    /**
     * 写入文档存储后回填的分块 ID 列表，空格分隔（对应 RagFlow Task.chunk_ids）
     */
    private String chunkIds;

    /**
     * 重试次数（对应 RagFlow Task.retry_count）
     */
    private Integer retryCount;
}