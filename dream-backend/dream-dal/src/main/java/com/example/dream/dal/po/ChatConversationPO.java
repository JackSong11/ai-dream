package com.example.dream.dal.po;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.dream.dal.po.base.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

/**
 * 会话（Session / Conversation）持久化对象。
 *
 * <p>对应 RagFlow db.Conversation 表，一个会话隶属于某个 Dialog，
 * 记录一轮多轮的消息列表与引用信息。业务主键沿用 RagFlow 语义使用 UUID 字符串。</p>
 *
 * <p>消息列表（message）与引用（reference）以 JSON 字符串存储，
 * 在 Service 层转换为业务对象后使用。</p>
 *
 * @author dream
 */
@EqualsAndHashCode(callSuper = true)
@Data
@TableName("chat_conversation")
public class ChatConversationPO extends BasePO {
    /**
     * 所属对话 ID（对应 RagFlow dialog_id）
     */
    private Long dialogId;

    /**
     * 归属用户 ID（对应 RagFlow user_id）
     */
    private String userId;

    /**
     * 会话名称（对应 RagFlow name）
     */
    private String name;

    /**
     * 消息列表（JSON 数组字符串，对应 RagFlow message）
     */
    private String message;

    /**
     * 引用列表（JSON 数组字符串，对应 RagFlow reference）
     */
    private String reference;
}