-- ----------------------------
-- Table structure for knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_base`;
CREATE TABLE `knowledge_base`
(
    `id`            bigint       NOT NULL COMMENT '主键ID',
    `name`          varchar(128) NOT NULL COMMENT '知识库名称',
    `description`   text COMMENT '知识库描述',
    `user_id`       varchar(50)           DEFAULT NULL COMMENT '归属的用户ID(对应 RagFlow tenant_id)',
    `permission`    varchar(16)           DEFAULT 'me' COMMENT '可见性 me/team(对应 RagFlow permission)',
    `parser_id`     varchar(32)           DEFAULT 'naive' COMMENT '默认解析器/分块方法(对应 RagFlow parser_id/chunk_method)',
    `parser_config` text COMMENT '默认解析器配置(JSON 字符串,对应 RagFlow parser_config)',
    `doc_num`       int                   DEFAULT 0 COMMENT '包含的文档总数',
    `token_num`     int                   DEFAULT 0 COMMENT '总 Token 数量',
    `chunk_num`     int                   DEFAULT 0 COMMENT '切片/片段总数',
    `delete_flag`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '知识库元数据表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for kb_document
-- ----------------------------
DROP TABLE IF EXISTS `kb_document`;
CREATE TABLE `kb_document`
(
    `id`            bigint       NOT NULL COMMENT '主键ID',
    `kb_id`         bigint                DEFAULT NULL COMMENT '所属知识库/数据集 ID',
    `parser_id`     varchar(32)           DEFAULT NULL COMMENT '解析器 ID(naive/picture/audio/presentation/email 等)',
    `parser_config` text COMMENT '解析器配置(JSON 字符串)',
    `type`          varchar(20)           DEFAULT NULL COMMENT '文档类型(doc/visual/aural/virtual/other 等)',
    `file_name`     varchar(255) NOT NULL COMMENT '文档名称',
    `suffix`        varchar(32)           DEFAULT NULL COMMENT '文件后缀(不含 ".")',
    `object_key`    varchar(500) NOT NULL COMMENT '对象存储中的位置/对象名',
    `size`          bigint                DEFAULT '0' COMMENT '文件大小,字节',
    `run`           int                   DEFAULT NULL COMMENT '运行/处理状态(0=未开始 UNSTART)',
    `progress`      decimal(5, 4)         DEFAULT '0.0000' COMMENT '解析进度 0~1(对齐 RagFlow document.progress,-1=失败)',
    `progress_msg`  text COMMENT '解析进度描述信息(对齐 RagFlow document.progress_msg,累积各阶段日志)',
    `status`        varchar(20)  NOT NULL COMMENT '文档状态',
    `chunk_count`   int                   DEFAULT '0' COMMENT '分块数量',
    `token_count`   int                   DEFAULT '0' COMMENT 'token 数量',
    `meta_fields`   text COMMENT '文档级元数据(JSON 字符串,对齐 RagFlow document.meta_fields,由 enable_metadata 抽取合并后落库)',
    `error_msg`     text COMMENT '错误信息',
    `delete_flag`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '文档表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for kb_task
-- ----------------------------
DROP TABLE IF EXISTS `kb_task`;
CREATE TABLE `kb_task`
(
    `id`            bigint    NOT NULL COMMENT '主键ID',
    `doc_id`        bigint    NOT NULL COMMENT '所属文档 ID',
    `from_page`     int                DEFAULT '0' COMMENT '起始页码(从0开始)',
    `to_page`       int                DEFAULT '-1' COMMENT '结束页码(-1表示到末尾)',
    `task_type`     varchar(32)        DEFAULT 'common' COMMENT '任务类型(common/raptor/graphrag)',
    `progress`      decimal(6, 4)      DEFAULT '0.0000' COMMENT '任务进度(0~1)',
    `progress_msg`  text COMMENT '进度描述信息',
    `chunk_ids`     mediumtext COMMENT '写入文档存储后回填的分块ID列表(空格分隔)',
    `retry_count`   int                DEFAULT '0' COMMENT '重试次数',
    `digest`        varchar(255)       DEFAULT NULL COMMENT '任务指纹',
    `delete_flag`   tinyint   NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)        DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)        DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime           DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_doc_id` (`doc_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '文档解析任务表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for biz_user
-- ----------------------------
DROP TABLE IF EXISTS `biz_user`;
CREATE TABLE `biz_user`
(
    `id`            bigint       NOT NULL COMMENT '主键ID',
    `user_id`       varchar(50)  NOT NULL,
    `password_hash` varchar(255) NOT NULL,
    `role`          enum ('ADMIN','USER','OPERATOR') DEFAULT 'USER',
    `status`        int                              DEFAULT NULL COMMENT '1-启用;0-禁用',
    `avatar_url`    mediumtext,
    `delete_flag`   tinyint      NOT NULL            DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)                      DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)                      DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime                         DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp    NOT NULL            DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_userId` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '用户表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chat_dialog
-- ----------------------------
DROP TABLE IF EXISTS `chat_dialog`;
CREATE TABLE `chat_dialog`
(
    `id`                        bigint       NOT NULL COMMENT '主键ID',
    `name`                      varchar(255)          DEFAULT NULL COMMENT '对话名称',
    `description`               text COMMENT '对话描述',
    `user_id`                   varchar(50)           DEFAULT NULL COMMENT '归属的用户ID',
    `llm_id`                    varchar(128)          DEFAULT NULL COMMENT '聊天模型 ID(对应 RagFlow llm_id)',
    `llm_setting`               text COMMENT 'LLM 生成参数配置(JSON 字符串)',
    `prompt_config`             text COMMENT 'Prompt 配置(JSON 字符串)',
    `kb_ids`                    text COMMENT '绑定的知识库 ID 列表(JSON 数组字符串)',
    `rerank_id`                 varchar(128)          DEFAULT NULL COMMENT 'rerank 模型 ID',
    `top_n`                     int                   DEFAULT NULL COMMENT '召回条数(对应 RagFlow top_n)',
    `top_k`                     int                   DEFAULT NULL COMMENT '向量召回条数(对应 RagFlow top_k)',
    `similarity_threshold`      double                DEFAULT NULL COMMENT '相似度阈值',
    `vector_similarity_weight`  double                DEFAULT NULL COMMENT '向量相似度权重',
    `delete_flag`               tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`                   varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`                    varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`              datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time`             timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '对话(聊天助手)表'
  ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chat_conversation
-- ----------------------------
DROP TABLE IF EXISTS `chat_conversation`;
CREATE TABLE `chat_conversation`
(
    `id`            bigint       NOT NULL COMMENT '主键ID',
    `dialog_id`     bigint                DEFAULT NULL COMMENT '所属对话 ID(对应 RagFlow dialog_id)',
    `user_id`       varchar(50)           DEFAULT NULL COMMENT '归属用户 ID(对应 RagFlow user_id)',
    `name`          varchar(255)          DEFAULT NULL COMMENT '会话名称',
    `message`       longtext COMMENT '消息列表(JSON 数组字符串)',
    `reference`     longtext COMMENT '引用列表(JSON 数组字符串)',
    `delete_flag`   tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)           DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)           DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_dialog_id` (`dialog_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '会话表'
  ROW_FORMAT = Dynamic;