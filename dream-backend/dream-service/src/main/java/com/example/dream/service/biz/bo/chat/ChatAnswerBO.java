package com.example.dream.service.biz.bo.chat;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 聊天生成结果业务对象。
 *
 * <p>对应 RagFlow async_chat 每次 yield 的 ans dict，经 structure_answer 结构化后
 * 包含 answer/reference 及会话/消息标识等字段。</p>
 *
 * @author dream
 */
@Data
public class ChatAnswerBO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生成的答案文本（增量或全量，对应 RagFlow ans["answer"]）
     */
    private String answer;

    /**
     * 引用信息（对应 RagFlow ans["reference"]）
     */
    private Map<String, Object> reference;

    /**
     * 关联的用户消息 ID（对应 RagFlow message id）
     */
    private String id;

    /**
     * 会话 ID（对应 RagFlow session_id/conversation_id）
     */
    private Long convId;

    /**
     * 对话 ID（对应 RagFlow dialog_id）
     */
    private Long dialogId;

    /**
     * 是否为最终结果（对应 RagFlow ans["final"]）
     */
    private Boolean finalFlag;

    /**
     * 思考开始标记（对应 RagFlow ans["start_to_think"]）
     */
    private Boolean startToThink;

    /**
     * 思考结束标记（对应 RagFlow ans["end_to_think"]）
     */
    private Boolean endToThink;

    /**
     * 附加扩展字段（对应 RagFlow ans 中其余动态字段）
     */
    private Map<String, Object> extra;
}