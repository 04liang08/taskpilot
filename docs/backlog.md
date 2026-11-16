# Backlog（Week 4 → 下一阶段）

> 状态：第 3 周评审修复（0.1~0.3、1.1~1.5）已完成，`mvn clean verify` 全绿，
> 双 scheduler + 双 executor 端到端验收两次通过（退出码 0）。
> `fix/review-round1` 已合并回 `main`，Week 3 收尾。
>
> **第 4 周 Day 1 已完成**：A-1（结果异步上报）与 A-2（死信告警）落地，
> 见 [ADR-019](design-decisions.md#adr-019结果异步上报走-rabbitmq手动触发也统一异步)。
> B-01 一并修掉了。下面是剩下的条目。

## A. Week 4 起点

| 顺序 | 方向 | 与现状的差距 | 状态 |
|---|---|---|---|
| 1 | **结果异步上报** | 执行器执行完走 MQ / 回调上报 | ✅ 已完成（4.1~4.4）：RabbitMQ 拓扑 + ACK 快回包 + 结果消费者 + 手动触发统一异步 |
| 2 | **死信告警** | 重试预算耗尽的 FAILED 记录进死信表 + 告警 webhook | ✅ 已完成（4.5）：`tp_dead_letter` + DLX 消费者 + fanout 告警 + 可选 webhook |
| 3 | **鉴权 / 压测 / CI** | 沿用 README 第 10 节「已知缺口」表的 Week 4 计划 | ⬜ 未开始 |

## B. 评审遗留（不阻塞合并）

### B-01 `markFailedForRetry` 缺状态前置条件，与 `finish` 不对称

- **状态**：✅ 已修（第 4 周顺手改的，正好碰这块代码）。
- **做法**：补 `status IN ('FAILED','TIMEOUT')` 前置条件，与 `finish` 对称；
  补了并发时序单测（`markFailedForRetryOnlyUpdatesAlreadyFailedRecords`）。
- **原风险**：极窄竞态下，迟到的失败事件（`RetryService.onShardFailed`）可能在 failover
  已用 `claimForRetryOn` 把记录抢成 RUNNING 之后到达，把记录覆写回 FAILED 并重新挂上
  `next_retry_time`，造成状态回退 / 重复重试。

### B-02 `fire()` 的并发重排保护是 check-then-act

- **位置**：`.../schedule/TriggerScheduler.java#fire`；0.1 引入的 9 行保护。
- **现状**：`wheel.scheduledTimeOf(taskId)` 与 `wheel.schedule(taskId, next)` 是两次独立 `synchronized` 调用，中间毫秒级窗口内若发生重排（改 cron / 与 DB 对账），仍会用旧 cron 覆盖新排期。
- **影响**：最坏一次错时触发，下个周期自愈；**该保护没有直接单测** —— 0.1 只更新了 `TimeWheelTest`，`TriggerSchedulerTest` 未改动。
- **建议**：把「检查 + 推进」收进 `TimeWheel` 的单个复合同步方法（如 `rescheduleIfAbsent(taskId, next)`）或引入版本号做 CAS；并给 `TriggerSchedulerTest` 补并发重排的直接单测。
- **优先级**：低。

### B-03 cron 无在线执行器时是终态 FAILED（既有语义，知晓即可）

- **位置**：`TriggerService.executeTrigger` → `ExecutionService.createFailed`。
- **现状**：1.5 已把 `triggerType` 修正为真实值，但 `createFailed` 仍用 UUID 幂等键、不挂 `next_retry_time` → 该次触发是终态 FAILED，靠下一个 cron 周期自愈。
- **结论**：**不改代码**，按既有语义记录。第 4 周的死信表没有把这类「调度前失败」纳入：
  它没有重试预算可言（压根没派发出去），落死信只会制造噪声。
- **优先级**：无（设计取舍）。

### B-04 `tp_task.task_name` 唯一索引形态决策（可选 2.4 未做）

- **现状**：`TaskService.checkNameUnique` 是 check-then-insert，并发下可插入重名任务；没有 `DuplicateKeyException → 40900` 兜底。现表带逻辑删除列 `deleted`。
- **待决策**：唯一键形态。直接 `UNIQUE(task_name)` 会和逻辑删除行冲突（同名任务删除后无法重建）；候选是 `UNIQUE(task_name, deleted)`（仍限制同名只能有一条删除记录）、`(task_name, deleted, id)` 生成列，或改用「仅对未删除行生效」的生成列唯一索引。
- **建议**：先出一条 ADR 明确语义与迁移方案，再改 DDL + 补 `DuplicateKeyException` 兜底测试。
- **优先级**：低，需先决策。

## C. 本地环境 / 卫生（非代码，不影响交付）

- `.tmp/` 下的 playwright 截图/脚本、浏览器 profile、node_modules 与临时 MySQL 数据目录均已清理；`.tmp/verify-cluster/`、`.tmp/verify-week3/`、`.tmp/verify-week4/` 保留作为验收日志（均已 gitignore）。
- 本机 3306 被 WorkBuddy 自身的 HTTP 服务占用、MySQL81 服务无法启动；E2E 曾用 `.tmp/mysql-data` 起 3307 隔离实例，通过 `-MysqlPort 3307` 传参，验收结束后已停止并删除。正常环境（MySQL 在 3306）直接跑默认参数即可。
- `.workbuddy/` 是本地工作区数据，未纳入版本控制。
- 第 4 周起 E2E 还需要 RabbitMQ（5672 / 15672）：`docker compose -f deploy/docker-compose.yml up -d rabbitmq`。
