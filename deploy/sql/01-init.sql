-- TaskPilot 初始化脚本（MySQL 8）：全新安装只跑本脚本。
-- 本文件已包含 Week 1~4 的全部表与列：tp_leader、tp_execution_log 的
-- fencing_token / leader_instance / next_retry_time / uk_execution_key / retry_count 等，
-- 以及第 4 周的 tp_dead_letter（死信表）。
-- 02-week2-leader.sql、03-week3-retry.sql、04-week4-deadletter.sql 是给「旧版本数据库」
-- 升级用的增量脚本：在全新库上再执行它们会报 table already exists / Duplicate column name。
-- 时区约定：容器/连接/JVM 统一 Asia/Shanghai，时间字段使用 DATETIME(3)。

CREATE DATABASE IF NOT EXISTS taskpilot
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE taskpilot;

CREATE TABLE IF NOT EXISTS tp_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
  task_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '任务分组',
  cron_expression VARCHAR(64) NOT NULL COMMENT 'Spring 6 段 cron：秒 分 时 日 月 周',
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'CRON' COMMENT 'CRON/FIXED_RATE/MANUAL',
  handler_name VARCHAR(128) NOT NULL COMMENT '执行器处理器名称',
  handler_param TEXT NULL COMMENT '处理器参数（JSON/字符串）',
  shard_total INT NOT NULL DEFAULT 1 COMMENT '分片总数，第 1 周固定 1',
  timeout_seconds INT NOT NULL DEFAULT 60 COMMENT '单次执行超时秒数',
  retry_max INT NOT NULL DEFAULT 0 COMMENT '最大重试次数',
  retry_interval_seconds INT NOT NULL DEFAULT 10 COMMENT '重试间隔秒数',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  next_trigger_time DATETIME(3) NULL COMMENT '下次触发时间，第 2 周使用',
  last_trigger_time DATETIME(3) NULL COMMENT '上次触发时间',
  description VARCHAR(512) NULL COMMENT '描述',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_task_name (task_name),
  KEY idx_status_next (status, next_trigger_time),
  KEY idx_group (task_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务定义';

CREATE TABLE IF NOT EXISTS tp_executor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_name VARCHAR(128) NOT NULL COMMENT '执行器应用名',
  executor_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '执行器分组',
  address VARCHAR(128) NOT NULL COMMENT 'host:port',
  status VARCHAR(16) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINE',
  last_heartbeat_time DATETIME(3) NOT NULL COMMENT '最后心跳时间',
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_address (app_name, address),
  KEY idx_status_heartbeat (status, last_heartbeat_time),
  KEY idx_group (executor_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '执行器注册表';

CREATE TABLE IF NOT EXISTS tp_execution_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  execution_key VARCHAR(128) NOT NULL COMMENT '幂等键：定时=taskId:triggerTime:shard；手动=UUID',
  task_id BIGINT NOT NULL,
  task_name VARCHAR(128) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL COMMENT 'CRON/FIXED_RATE/MANUAL',
  trigger_time DATETIME(3) NOT NULL,
  start_time DATETIME(3) NULL,
  end_time DATETIME(3) NULL,
  executor_id BIGINT NULL,
  executor_address VARCHAR(128) NULL,
  shard_index INT NOT NULL DEFAULT 0,
  shard_total INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
    COMMENT 'RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELED',
  retry_count INT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '产生该记录的 leader fencing token',
  leader_instance VARCHAR(64) NULL COMMENT '产生该记录的调度实例',
  next_retry_time DATETIME(3) NULL COMMENT '下次退避重试时间；为空表示已终态',
  duration_ms BIGINT NULL,
  error_message TEXT NULL,
  result_summary VARCHAR(1024) NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_execution_key (execution_key),
  KEY idx_task_trigger (task_id, trigger_time),
  KEY idx_status_create (status, create_time),
  KEY idx_executor (executor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '执行记录';

-- 调度集群 leader 状态（单行表，id 固定为 1）。
-- fencing_token 单调递增：旧 leader 即使还持有过期的分布式锁，写操作也会被这里拒绝。
CREATE TABLE IF NOT EXISTS tp_leader (
  id INT NOT NULL DEFAULT 1 COMMENT '固定为 1，单行表',
  instance_id VARCHAR(64) NOT NULL COMMENT '当前 leader 实例标识',
  fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT 'fencing token，单调递增',
  leader_time DATETIME(3) NOT NULL COMMENT '成为 leader 的时间',
  fence_check_time DATETIME(3) NULL COMMENT '最近一次 fencing 校验时间',
  fence_seq BIGINT NOT NULL DEFAULT 0 COMMENT 'fencing 校验计数器，保证每次校验都会改动行',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '调度集群 leader 与 fencing token';

-- 死信表：重试预算耗尽、或结果消息处理失败（毒消息）的执行记录。
-- execution_id 唯一：同一条执行记录只落一行死信，重复上报不会堆重复行。
CREATE TABLE IF NOT EXISTS tp_dead_letter (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  execution_id BIGINT NOT NULL COMMENT '执行记录 ID；同一条记录只落一行死信',
  task_id BIGINT NOT NULL COMMENT '任务 ID',
  task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
  shard_index INT NOT NULL DEFAULT 0 COMMENT '分片号',
  shard_total INT NOT NULL DEFAULT 1 COMMENT '分片总数',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '进入死信时已重试次数',
  last_error TEXT NULL COMMENT '最后一次失败原因',
  dead_time DATETIME(3) NOT NULL COMMENT '进入死信的时间',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dead_execution (execution_id),
  KEY idx_task (task_id),
  KEY idx_dead_time (dead_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '死信记录：重试耗尽或结果消息处理失败';