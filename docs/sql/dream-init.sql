-- ----------------------------
-- Table structure for knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `knowledge_base`;
CREATE TABLE `knowledge_base`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name`          varchar(128) NOT NULL COMMENT '知识库名称',
    `description`   text COMMENT '知识库描述',
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
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `kb_id`         varchar(64)           DEFAULT NULL COMMENT '所属知识库/数据集 ID',
    `parser_id`     varchar(32)           DEFAULT NULL COMMENT '解析器 ID(naive/picture/audio/presentation/email 等)',
    `parser_config` text COMMENT '解析器配置(JSON 字符串)',
    `type`          varchar(20)           DEFAULT NULL COMMENT '文档类型(doc/visual/aural/virtual/other 等)',
    `file_name`     varchar(255) NOT NULL COMMENT '文档名称',
    `suffix`        varchar(32)           DEFAULT NULL COMMENT '文件后缀(不含 ".")',
    `object_key`    varchar(500) NOT NULL COMMENT '对象存储中的位置/对象名',
    `size`          bigint                DEFAULT '0' COMMENT '文件大小,字节',
    `run`           int                   DEFAULT NULL COMMENT '运行/处理状态(0=未开始 UNSTART)',
    `status`        varchar(20)  NOT NULL COMMENT '文档状态',
    `chunk_count`   int                   DEFAULT '0' COMMENT '分块数量',
    `token_count`   int                   DEFAULT '0' COMMENT 'token 数量',
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
    `id`            bigint      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `doc_id`        varchar(64) NOT NULL COMMENT '所属文档 ID',
    `from_page`     int                  DEFAULT '0' COMMENT '起始页码(从0开始)',
    `to_page`       int                  DEFAULT '-1' COMMENT '结束页码(-1表示到末尾)',
    `task_type`     varchar(32)          DEFAULT 'common' COMMENT '任务类型(common/raptor/graphrag)',
    `progress`      decimal(6, 4)        DEFAULT '0.0000' COMMENT '任务进度(0~1)',
    `progress_msg`  text COMMENT '进度描述信息',
    `chunk_ids`     mediumtext COMMENT '写入文档存储后回填的分块ID列表(空格分隔)',
    `retry_count`   int                  DEFAULT '0' COMMENT '重试次数',
    `delete_flag`   tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除字段 0:代表有效， 1:代表逻辑删除',
    `creator`       varchar(20)          DEFAULT NULL COMMENT '创建人',
    `editor`        varchar(20)          DEFAULT NULL COMMENT '修改人',
    `created_time`  datetime             DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `modified_time` timestamp   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    PRIMARY KEY (`id`),
    KEY `idx_doc_id` (`doc_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin COMMENT = '文档解析任务表'
  ROW_FORMAT = Dynamic;

CREATE TABLE `biz_user`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
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