-- Week 4 Day 1 增量迁移：新增死信表 tp_dead_letter。
-- 适用范围：仅 Week 3 时期建的老库（当时的 01-init.sql 还没有死信表）。
-- 全新安装请只执行当前版本的 01-init.sql —— 它已包含 tp_dead_letter；
-- 在全新库上再跑本脚本会报 "table already exists"。
-- 注意：MySQL 不支持 CREATE TABLE IF NOT EXISTS 之外的"已存在就跳过"，
-- 本脚本用 IF NOT EXISTS 写法，重复执行是安全的。

CREATE DATABASE IF NOT EXISTS taskpilot
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE taskpilot;

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
