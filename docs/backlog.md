# Backlog（Week 4 → 下一阶段）

> 状态：第 3 周评审修复（0.1~0.3、1.1~1.5）已完成，`mvn clean verify` 全绿，
> 双 scheduler + 双 executor 端到端验收两次通过（退出码 0）。
> `fix/review-round1` 已合并回 `main`，Week 3 收尾。
>
> **第 4 周 Day 1 已完成**：A-1（结果异步上报）与 A-2（死信告警）落地，
> 见 [ADR-019](design-decisions.md#adr-019结果异步上报走-rabbitmq手动触发也统一异步)。
> B-01 一并修掉了。
>
> **第 4 周 Day 1 交付已通过评审方复审**（7 个 commit 逐项通过，`mvn clean verify` 148 测试全绿，
> `verify-week4.ps1` 五场景证据核实）。复审新增遗留 **B-05 ~ B-07**（见下），
> 均不影响合并、不影响执行正确性。
>
> **第 4 周 Day 2 追加**：A-3 里的「鉴权」（ADR-020）与「CI」（ADR-021）已落地。
>
> **第 4 周 Day 3 追加**：A-3 的「压测」（4.8）完成——[压测报告](perf-report.md) 给出
> 50/100/200 并发实测（TPS 111/200/267，0 错误）与异步管道持续负载观测；压测中新发现 B-08/B-09（见下）。
> **A-3 复审已通过（可合并）**；复审新记 **B-10**（鉴权 filter 原始 URI 匹配的编码绕过加固缝，见下），不阻塞合并。
> 下面是剩下的条目。

## A. Week 4 起点

| 顺序 | 方向 | 与现状的差距 | 状态 |
|---|---|---|---|
| 1 | **结果异步上报** | 执行器执行完走 MQ / 回调上报 | ✅ 已完成（4.1~4.4）：RabbitMQ 拓扑 + ACK 快回包 + 结果消费者 + 手动触发统一异步 |
| 2 | **死信告警** | 重试预算耗尽的 FAILED 记录进死信表 + 告警 webhook | ✅ 已完成（4.5）：`tp_dead_letter` + DLX 消费者 + fanout 告警 + 可选 webhook |
| 3 | **鉴权 / 压测 / CI** | 沿用 README 第 10 节「已知缺口」表的 Week 4 计划 | ✅ 已完成（4.7/4.8/4.9）：鉴权（ADR-020）+ JMeter 压测（[perf-report](perf-report.md)）+ CI（ADR-021） |

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

### B-05 `markRetryExhausted` 无条件落死信

- **位置**：`taskpilot-scheduler/src/main/java/com/taskpilot/scheduler/service/ExecutionService.java#markRetryExhausted`。
- **现状**：守卫 UPDATE（`status IN ('FAILED','TIMEOUT')`）命中 0 行时，**仍然**调 `deadLetterService.record()`——死信写入没有以"确实收成终态"为前提。
- **风险**：极窄竞态——记录已被并发 claim 成 RUNNING、随后重试成功，却留下一条"重试耗尽"死信。**取证失真，不影响执行正确性**（死信表只是给人看的记录，不参与任何调度判断）。
- **建议**：`record` 以 affected > 0 为前提（或落库前复核记录确为终态）；补一个时序单测
  （UPDATE 命中 0 行 → 不落死信）。
- **优先级**：低。

### B-06 跨 attempt 的迟到结果无关联

- **位置**：`common/.../dto/ExecutionResultMessage.java`、`scheduler/.../service/ResultProcessor.java#process`、
  `scheduler/.../service/ExecutionService.java#finishIfRunning`。
- **现状**：结果消息不带 `retryCount` / attempt 维度（只有 executionId、taskId、status、durationMs、
  errorMessage、resultSummary、executorAddress、reportedAt），而 `finishIfRunning` 只查 `status = RUNNING`。
  于是 **attempt N 的迟到结果可能收尾 attempt N+1 的 RUNNING 记录**。
- **影响**：在"至少一次 + 业务幂等"语义内（终态仍然只写一次），但**记录终态可能来自旧 attempt**：
  `executor_id` / `executor_address` 已经被失败转移改指向新执行器，而终态与结果摘要却来自上一轮那次执行。
  `docs/architecture.md` 的边界表写了"可能执行两遍"，**没写这个取证上的细微处**。
- **建议**：
  - **短期（文档先行）**：边界表补一句——"迟到结果可能覆盖新一轮 attempt 的记录，终态与 `executor` 列可能来自不同轮次"；
  - **长期**：消息带 attempt 维度（发消息时把 `retry_count` 带上去），`finish` 的前置条件加上 attempt 匹配。
- **优先级**：低（文档先行）。

### B-07 `ShardFailedEvent` 在事务内同步发布（可选加固）

- **位置**：`scheduler/.../service/ResultProcessor.java#process`（`@Transactional`）内的 `publishShardFailed`。
- **现状**：失败事件在事务提交**之前**同步发布，监听器（`RetryService.onShardFailed` /
  `ShardFailoverService.onShardFailed`）跑在同一个事务里。失败转移的异步路径可能读到未提交状态，
  **靠现有守卫（`status IN (FAILED,TIMEOUT)` 前置条件）收敛，不是 bug**。
- **建议**：改成 `@TransactionalEventListener(phase = AFTER_COMMIT)` 更干净——监听器只在提交后跑，
  语义上"失败已经落库了才通知别人"也更直观。
- **优先级**：可选。

### B-08 leader 选举状态对 DB 重置无自愈（4.8 压测中发现）

- **位置**：`scheduler/.../service/TriggerService`（fencing 校验）、`scheduler/.../leader/LeaderElectionService`。
- **现象**：压测中直接重置 MySQL（换 8.0 容器）后，`tp_leader` 行丢失；进程内存里仍是 leader
  （Redis 锁与 fencing token 未受影响），但每次触发都被 fencing 校验拒绝（40900「本实例已不是 leader」），
  **所有定时与手动触发全停**，直到重启 scheduler 重新选举写回 `tp_leader` 才自愈。
- **影响**：仅 DB 被外部重置/迁移丢行的运维场景，正常故障转移（failover 换主）不受影响；
  但表现为「集群明明活着却全员拒绝触发」，排查成本高。
- **建议**：选举循环里检测到 Redis 持锁但 `tp_leader` 无本实例行时，定向补写（或 fencing 校验改为
  以 Redis token 为准、DB 行仅展示用）；配一个「leader 行丢失 → 自愈补写」的单测。
- **优先级**：低（运维边缘场景，不影响执行正确性）。

### B-09 陈旧 ONLINE 执行器行的离线判定会误杀刚派发的在途记录（4.8 验收回归中发现）

- **位置**：`scheduler/.../executor/ExecutorStatusListener`（心跳超时 → 下线事件）、
  `scheduler/.../shard/ShardFailoverService`（按 executor 地址找 RUNNING 记录做转移）。
- **现象**：执行器进程被硬杀时 `tp_executor` 行停留在 ONLINE、`last_heartbeat_time` 陈旧；同地址实例重启
  并注册（注册按 `(appName,address)` 复用行）与调度中心的心跳超时扫描存在竞态——实测 verify-week4 场景①
  里，新执行器刚接住派发（15:28:31）、其行在注册完成前被判离线（15:28:33），failover 按地址命中这条
  在途记录，`retryMax=0` 直接标 FAILED 落死信；执行器随后跑完回传，结果被 `ResultProcessor` 忽略。
  清空 `tp_executor` 后重跑验收五场景全过（退出码 0），即复现条件是「陈旧 ONLINE 行 + 竞态」，非必现。
- **影响**：执行器重启后最初一个心跳窗口内派发的任务可能被误标 FAILED（取证失真 + 误告警/误死信），
  实际业务代码仍执行完成、结果被丢弃。不影响数据正确性主路径（幂等键仍成立）。
- **建议**：下线事件里若发现同 `(appName,address)` 行在本调度中心启动周期内刚重新注册成功（或恢复心跳），
  跳过对在途记录的 failover；或注册 upsert 时立即刷新 `last_heartbeat_time` 再交给扫描线程比较。
- **优先级**：中低（窗口小、可被下一次验收重跑掩盖，但滚动重启场景会踩）。

### B-10 鉴权 filter 用原始 URI 匹配，百分号编码前缀可绕过（4.7 复审发现）

- **状态**：✅ 已修（Week 4 收尾补丁：两处 filter 改为 `getServletPath()` 解码路径匹配，
  两侧各补「编码前缀仍被拒」单测；ADR-020 同步「按解码路径匹配」一节）。
- **位置**：`scheduler/.../filter/AuthTokenFilter.java#requiresToken`、`executor/.../filter/AuthTokenFilter.java#requiresToken`。
- **现状**：两者都对 `request.getRequestURI()`（未解码）做 `equals`/`startsWith`。
  请求 `/api/v1/%65xecutor/execute`（或 `/api/v1/executors/%72egister`）时，原始 URI 不含字面前缀 →
  filter 放行；Spring 的 PathPattern 按解码后路径仍映射到处理器 → 不带 token 也能调内部接口。
- **影响**：低。ADR-020 威胁模型是内网 demo，真实攻击面（无/错 token、伪造下线）已挡住；
  这是一条理论加固缝，不影响现有正确性与验收。
- **建议**：改用解码后路径匹配（`request.getServletPath()` 或 `ServletRequestPathUtils.getCachedPath(request)`），
  补一个「编码前缀仍被拒」的单测。
- **优先级**：低（可选加固）。

## C. 本地环境 / 卫生（非代码，不影响交付）

- `.tmp/` 下的 playwright 截图/脚本、浏览器 profile、node_modules 与临时 MySQL 数据目录均已清理；`.tmp/verify-cluster/`、`.tmp/verify-week3/`、`.tmp/verify-week4/` 保留作为验收日志（均已 gitignore）。
- 本机 3306 被 WorkBuddy 自身的 HTTP 服务占用、MySQL81 服务无法启动；E2E 曾用 `.tmp/mysql-data` 起 3307 隔离实例，通过 `-MysqlPort 3307` 传参，验收结束后已停止并删除。正常环境（MySQL 在 3306）直接跑默认参数即可。
- `.workbuddy/` 是本地工作区数据，未纳入版本控制。
- 第 4 周起 E2E 还需要 RabbitMQ（5672 / 15672）：`docker compose -f deploy/docker-compose.yml up -d rabbitmq`。
- 4.8 压测用 MySQL 是 `docker run -d --name tp-perf-mysql -p 3307:3306 ... mysql:8.0`（仅挂载
  `deploy/sql/01-init.sql`——整套 `deploy/sql` 目录挂进 initdb 会因 `02-week2-leader.sql` 对 01 已含列重复
  ALTER 而启动失败退出）；本机原生 mysqld 8.1.0 在 ~100/s 写入下 APPCRASH，勿再用作压测库。
  压测/验收完直接 `docker rm -f tp-perf-mysql` 即可，产物在 `.tmp/perf-*`（gitignore）。
