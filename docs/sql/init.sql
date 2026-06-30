-- ----------------------------
-- Table structure for knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_base`;
CREATE TABLE `knowledge_base`
(
    `id`                       bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `avatar`                   text,
    `tenant_id`                varchar(32)  NOT NULL,
    `name`                     varchar(128) NOT NULL,
    `language`                 varchar(32)           DEFAULT NULL,
    `description`              text,
    `embd_id`                  varchar(128) NOT NULL,
    `tenant_embd_id`           int                   DEFAULT NULL,
    `permission`               varchar(16)  NOT NULL,
    `created_by`               varchar(32)  NOT NULL,
    `doc_num`                  int          NOT NULL,
    `token_num`                int          NOT NULL COMMENT 'token数，在把chunk转为embedding向量是会加一次',
    `chunk_num`                int          NOT NULL COMMENT '文档存到ES中的chunks数，应该也是累加的，这里算得是整个知识库的chunks数',
    `similarity_threshold`     float        NOT NULL,
    `vector_similarity_weight` float        NOT NULL,
    `parser_id`                varchar(32)  NOT NULL,
    `pipeline_id`              varchar(32)           DEFAULT NULL,
    `parser_config`            longtext     NOT NULL,
    `pagerank`                 int          NOT NULL,
    `graphrag_task_id`         varchar(32)           DEFAULT NULL,
    `graphrag_task_finish_at`  datetime              DEFAULT NULL,
    `raptor_task_id`           varchar(32)           DEFAULT NULL,
    `raptor_task_finish_at`    datetime              DEFAULT NULL,
    `mindmap_task_id`          varchar(32)           DEFAULT NULL,
    `mindmap_task_finish_at`   datetime              DEFAULT NULL,
    `status`                   varchar(1)            DEFAULT NULL,
    `delete_flag`              tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`                  varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`                   varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`             datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time`            timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT = '知识库表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for document
-- ----------------------------
DROP TABLE IF EXISTS `document`;
CREATE TABLE `document`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `thumbnail`        text,
    `kb_id`            varchar(256) NOT NULL COMMENT '知识库ID',
    `parser_id`        varchar(32)  NOT NULL,
    `pipeline_id`      varchar(32)           DEFAULT NULL,
    `parser_config`    longtext     NOT NULL,
    `source_type`      varchar(128) NOT NULL,
    `type`             varchar(32)  NOT NULL COMMENT '文件类型(pdf,excel,word)',
    `name`             varchar(255)          DEFAULT NULL COMMENT '文件名',
    `location`         varchar(255)          DEFAULT NULL,
    `size`             bigint       NOT NULL,
    `token_num`        int          NOT NULL COMMENT 'token数，在把chunk转为embedding向量是会加一次',
    `chunk_num`        int          NOT NULL COMMENT '这个文档存到ES中的chunks数',
    `progress`         float        NOT NULL,
    `progress_msg`     text,
    `process_begin_at` datetime              DEFAULT NULL,
    `process_duration` float        NOT NULL,
    `suffix`           varchar(32)  NOT NULL,
    `content_hash`     varchar(32)           DEFAULT NULL,
    `run`              varchar(1)            DEFAULT NULL COMMENT '任务状态(0-未开始,1-运行中,2-已取消,3-已完成,4-失败,5-已调度)',
    `status`           varchar(1)            DEFAULT NULL,
    `delete_flag`      tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`          varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`           varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`     datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time`    timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT = 'document' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for task
-- ----------------------------
DROP TABLE IF EXISTS `task`;
CREATE TABLE `task`
(
    `id`               bigint      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `doc_id`           varchar(32) NOT NULL COMMENT '文档ID',
    `from_page`        int         NOT NULL DEFAULT 0 COMMENT '起始页',
    `to_page`          int         NOT NULL COMMENT '结束页',
    `task_type`        varchar(32) NOT NULL COMMENT '任务类型',
    `priority`         int         NOT NULL DEFAULT 0 COMMENT '优先级',
    `begin_at`         datetime             DEFAULT NULL COMMENT '任务开始时间',
    `process_duration` float       NOT NULL,
    `progress`         float       NOT NULL DEFAULT 0 COMMENT '任务进度:0-1.0',
    `progress_msg`     text COMMENT '任务日志',
    `retry_count`      int         NOT NULL default 0,
    `digest`           text COMMENT '任务指纹',
    `chunk_ids`        longtext,
    `delete_flag`      tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`          varchar(20)          DEFAULT NULL COMMENT '创建人',
    `editor`           varchar(20)          DEFAULT NULL COMMENT '修改人',
    `created_time`     datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time`    timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT = 'task' ROW_FORMAT = Dynamic;



