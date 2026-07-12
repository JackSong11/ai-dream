package com.example.dream.common.constant;

/**
 * 文档解析任务相关常量。
 *
 * <p>定义 Redis Stream 队列、消费者组以及消息字段等常量，
 * 供生产端（dream-service）与消费端（dream-processor）共享，
 * 对应 RagFlow 中 svr_queue / SVR_CONSUMER_GROUP_NAME 的语义。</p>
 *
 * @author dream
 */
public final class DocTaskConstants {

    private DocTaskConstants() {
    }

    /**
     * 文档解析任务队列名（Redis Stream key）。
     * <p>对应 RagFlow settings.get_svr_queue_names。</p>
     */
    public static final String SVR_QUEUE = "dream_svr_queue";

    /**
     * 消费者组名。
     * <p>对应 RagFlow SVR_CONSUMER_GROUP_NAME。</p>
     */
    public static final String SVR_CONSUMER_GROUP = "dream_svr_consumer_group";

    /**
     * Stream 消息中承载任务 JSON 的字段名。
     * <p>消息体仅放一个字段，值为任务的 JSON 字符串，避免多字段序列化差异。</p>
     */
    public static final String MSG_FIELD_PAYLOAD = "payload";

    /**
     * ES 索引名前缀，对应 RagFlow search.index_name(user_id) = "ragflow_{userId}"。
     */
    public static final String INDEX_NAME_PREFIX = "ragflow_";

    /**
     * 任务取消标记的 Redis key 后缀，对应 RagFlow has_canceled 检查的 "{task_id}-cancel"。
     */
    public static final String CANCEL_KEY_SUFFIX = "-cancel";

    /**
     * 构建 ES 索引名，对应 RagFlow search.index_name(userId)。
     *
     * @param userId 用户 ID
     * @return 索引名
     */
    public static String indexName(String userId) {
        return INDEX_NAME_PREFIX + userId;
    }

    /**
     * 构建任务取消标记的 Redis key，对应 RagFlow has_canceled 的 "{task_id}-cancel"。
     *
     * @param taskId 任务 ID
     * @return 取消标记 key
     */
    public static String cancelKey(Long taskId) {
        return taskId + CANCEL_KEY_SUFFIX;
    }
}