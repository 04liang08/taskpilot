-- Week 2 Day 3 增量迁移：leader 状态表 + 执行记录里的 fencing 信息。
-- 已经按 01-init.sql 建过库的环境执行本脚本；全新安装直接用 01-init.sql 即可。
-- 注意：MySQL 不支持 ALTER TABLE ADD COLUMN IF NOT EXISTS，本脚本只执行一次。

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

ALTER TABLE tp_execution_log
  ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0 COMMENT '产生该记录的 leader fencing token' AFTER retry_count,
  ADD COLUMN leader_instance VARCHAR(64) NULL COMMENT '产生该记录的调度实例' AFTER fencing_token;
