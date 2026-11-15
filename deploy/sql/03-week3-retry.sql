-- Week 3 Day 4 增量迁移：执行记录增加退避重试时间。
-- 已经按 01-init.sql 建过库的环境执行本脚本；全新安装直接用 01-init.sql 即可。
-- 注意：MySQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

ALTER TABLE tp_execution_log
  ADD COLUMN next_retry_time DATETIME(3) NULL COMMENT '下次退避重试时间；为空表示已终态' AFTER leader_instance;
