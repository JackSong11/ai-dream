CREATE TABLE `checkpoint_blobs`
(
    `thread_id`          varchar(150)  NOT NULL,
    `checkpoint_ns`      varchar(2000) NOT NULL DEFAULT '',
    `channel`            varchar(150)  NOT NULL,
    `version`            varchar(150)  NOT NULL,
    `type`               varchar(150)  NOT NULL,
    `blob`               longblob,
    `checkpoint_ns_hash` binary(16)    NOT NULL,
    PRIMARY KEY (`thread_id`, `checkpoint_ns_hash`, `channel`, `version`),
    KEY `checkpoint_blobs_thread_id_idx` (`thread_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;



CREATE TABLE `checkpoint_writes`
(
    `thread_id`          varchar(150)  NOT NULL,
    `checkpoint_ns`      varchar(2000) NOT NULL DEFAULT '',
    `checkpoint_id`      varchar(150)  NOT NULL,
    `task_id`            varchar(150)  NOT NULL,
    `idx`                int           NOT NULL,
    `channel`            varchar(150)  NOT NULL,
    `type`               varchar(150)           DEFAULT NULL,
    `blob`               longblob      NOT NULL,
    `checkpoint_ns_hash` binary(16)    NOT NULL,
    `task_path`          varchar(2000) NOT NULL DEFAULT '',
    PRIMARY KEY (`thread_id`, `checkpoint_ns_hash`, `checkpoint_id`, `task_id`, `idx`),
    KEY `checkpoint_writes_thread_id_idx` (`thread_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;


-- ----------------------------
-- 核心状态表：存储图在某个时间点的核心状态（State Snapshot）和元数据。每次 Agent 节点执行完毕，都会在这里生成一条记录。
-- ----------------------------
CREATE TABLE `checkpoints`
(
    `thread_id`            varchar(150)  NOT NULL,
    `checkpoint_ns`        varchar(2000) NOT NULL DEFAULT '',
    `checkpoint_id`        varchar(150)  NOT NULL,
    `parent_checkpoint_id` varchar(150)           DEFAULT NULL,
    `type`                 varchar(150)           DEFAULT NULL,
    `checkpoint`           json          NOT NULL,
    `metadata`             json          NOT NULL DEFAULT (_utf8mb4'{}'),
    `checkpoint_ns_hash`   binary(16)    NOT NULL,
    PRIMARY KEY (`thread_id`, `checkpoint_ns_hash`, `checkpoint_id`),
    KEY `checkpoints_thread_id_idx` (`thread_id`),
    KEY `checkpoints_checkpoint_id_idx` (`checkpoint_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;





-- ----------------------------
-- 没用的表：记录和控制当前数据库中 LangGraph 检查点表的“表结构版本”（Schema Version），用以支持数据库的自动升级和向后兼容。
-- ----------------------------
CREATE TABLE `checkpoint_migrations`
(
    `v` int NOT NULL,
    PRIMARY KEY (`v`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;