-- Week 3 Day 4 增量迁移：执行记录增加退避重试时间。
-- 适用范围：仅 Week 2 时期建的老库（当时的 01-init.sql 还没有 next_retry_time）。
-- 全新安装请只执行当前版本的 01-init.sql —— 它已包含 next_retry_time 列；
-- 在全新库上再跑本脚本会报 "Duplicate column name 'next_retry_time'"。
-- 注意：MySQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

ALTER TABLE tp_execution_log
  ADD COLUMN next_retry_time DATETIME(3) NULL COMMENT '下次退避重试时间；为空表示已终态' AFTER leader_instance;
