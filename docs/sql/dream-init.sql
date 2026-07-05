-- ----------------------------
-- Table structure for kb_document
-- ----------------------------
DROP TABLE IF EXISTS `kb_document`;
CREATE TABLE `kb_document`
(
    `id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `file_name`     varchar(255) NOT NULL COMMENT '文档名称',
    `object_key`    varchar(500) NOT NULL,
    `status`        varchar(20)  NOT NULL,
    `chunk_count`   int                   DEFAULT '0' COMMENT '分块数量',
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