# TaskPilot 架构说明

> 本文描述当前已落地的架构，覆盖第 1~3 周，随每周迭代更新。

## 1. 总体结构

```text
┌─────────────┐        REST         ┌──────────────────────┐
│ 管理页面/curl│ ──────────────────→ │ scheduler-center:8080│
└─────────────┘                     │                      │
                                    │  TaskService         │
                                    │  TriggerService      │
                                    │  ExecutorRegistry    │
                                    │  ExecutionService    │
                                    └───────┬──────────────┘
                                            │
                        ┌───────────────────┼────────────────────┐
                        │                   │                    │
                   MySQL 8              Redis 7           RestClient
              task/executor/         (幂等/选主，         按地址调用
              execution_log           第 2 周起)              │
                                            ┌────────────────▼─────────────┐
                                            │ executor:8081（可多实例）     │
                                            │ HandlerRegistry              │
                                            │ demo/sleep/http handlers     │
                                            └──────────────────────────────┘
```

## 2. 组件职责

### scheduler-center

- 任务定义管理：CRUD、启停、cron 校验。
- 触发：手动触发 + 自实现时间轮的 cron 定时触发；第 2 周后半段接入领导选举。
- 分片：按策略把一次触发拆成 N 个分片，每个分片一条执行记录、独立调度。
- 执行器注册表：注册 / 心跳 / 超时下线；第 3 周扩展为分片与失败转移依据。
- 执行记录：先落 `RUNNING` 再派发，第 4 周起结果经 RabbitMQ 异步回来，最终更新为 `SUCCESS/FAILED/TIMEOUT`。
- 管理端：REST API + `static/index.html` 极简演示页。

### executor

- 启动时向 scheduler 注册自身地址；每 10 秒心跳。
- 支持配置多个调度中心地址：逐个尝试，任意一个通即可，避免单调度中心故障导致执行器被判离线。
- 接收 `POST /api/v1/executor/execute`，按 `handlerName` 从注册表找到处理器并执行。
- 线程池隔离，结果同步返回（第 3 周再考虑异步与超时控制）。
- 无状态，可水平扩展；地址必须能被调度中心访问。

### common

- 仅跨模块共享的 API 响应、分页、枚举、错误码、异常、JSON 工具。
- 不依赖 Spring Web，不放 Entity/Mapper。

## 3. 关键链路（第 1 周目标态）

### 3.1 执行器注册 / 心跳 / 下线

```text
executor 启动
  → POST /api/v1/executors/register {appName, group, address}
  → scheduler upsert tp_executor，status=ONLINE，last_heartbeat_time=now
  → 每 10s POST /api/v1/executors/heartbeat
  → scheduler 每 10s 检查：now - last_heartbeat_time > 30s → OFFLINE
```

### 3.2 手动触发

```text
POST /api/v1/tasks/{id}/trigger
  → 校验任务存在且 ENABLED
  → 选择在线执行器（轮询/随机）
  → 先插入 tp_execution_log(status=RUNNING, execution_key=UUID)
  → RestClient POST http://{executor.address}/api/v1/executor/execute
  → 根据结果更新 SUCCESS / FAILED / TIMEOUT
```

先落库再调用，保证超时或进程崩溃时仍有执行记录可追溯。

### 3.3 领导选举（集群只触发一次）

```text
每个 scheduler 实例
  → 每 2 秒尝试抢 Redis 锁 taskpilot:scheduler:leader（Redisson RLock，watchdog 自动续期）
  → 抢到后领 token = max(Redis 计数器, tp_leader.fencing_token + 1)
  → 写 tp_leader（instance_id + fencing_token）；token 落后于库里的值则放弃
  → 成为 leader：按 DB 重建时间轮，开始触发
  → 失去锁：降级为 follower，清空时间轮，不再触发

定时触发落库前
  → fencing 校验：UPDATE tp_leader ... WHERE fencing_token <= 我的 token
  → 影响行数为 0 说明有更新的 leader → 拒绝本次触发（40900）
```

关键点：锁解决"同时只有一个 leader"，fencing token 解决"过期 leader 复活后继续写"。两者缺一不可。

### 3.4 定时触发（时间轮）

```text
scheduler 启动
  → 从 tp_task 读出 ENABLED + CRON 的任务
  → next_trigger_time 在未来就沿用；已过期（停过机）就跳到下一个未来时间，不补跑
  → 按 next_trigger_time 放入时间轮槽位（slot = epochSecond % 60）

每秒 tick
  → 只扫描当前秒对应的槽位（不做全表扫描）
  → 到点的任务 → 提交到独立触发线程池（时间轮线程不等待执行结果）
  → 执行记录落 RUNNING → 调用执行器 → 更新 SUCCESS/FAILED/TIMEOUT
  → 计算下一个 cron 时间 → 写回 next_trigger_time → 重新入轮

任务新建/更新/启停
  → 发布事务提交后的事件（TaskScheduleEvent）
  → 调度器据此重排或移出时间轮
```

关键点：DB 是真相源，时间轮只是内存索引；进程重启后按 `next_trigger_time` 重建，天然具备重启补偿能力。

> 只有 leader 推轮子（见 3.3）；follower 拿到 leader 身份时会重新按 DB 重建时间轮。

### 3.5 分片触发（Week 3）

```text
一次触发（手动或定时）
  → shardTotal = 任务配置的分片数
  → 按策略分配：平均 / 哈希 / 广播（taskpilot.scheduler.shard-allocation）
  → 每个分片落一条 tp_execution_log（shard_index / shard_total / executor_id）
  → 每个分片独立调用对应执行器，互不影响
```

分片粒度的执行记录是失败转移的基础：知道"哪个分片在哪个执行器上"，执行器下线时才能精确重分配那几个分片。

### 3.6 失败转移与退避重试（Week 3）

```text
分片失败 / 执行器心跳超时下线
  → markStaleOffline 逐行条件 UPDATE，affected=1 才发下线事件（心跳恢复窗口内不误发）
  → ExecutorStatusListener（@Order 最高）先收尾：RUNNING → FAILED，
    并给 CRON 记录挂上 next_retry_time 兜底
  → ShardFailoverService（@Order 最低）再挑出未结束的记录：
      原执行器仍在线   → 不转移，交给原地退避重试
      原执行器已下线   → 一条 UPDATE 原子抢占（claimForRetryOn），
                        同时把 executor_id / executor_address 改指向目标执行器
                      → 复用同一条记录 dispatch（retry_count + 1），结果同样经 MQ 回来
  → 原执行器在线的业务失败 → RetryService 按 interval × 2^已重试次数 退避，
    @Scheduled / retryScheduler 线程只负责读库与排队，真正执行提交 shardExecutor
  → 重试预算耗尽 → 清掉 next_retry_time，落终态（死信表 + 告警）
```

关键点：

- **收尾必须先于转移**：否则 RUNNING 记录抢不动 `claimForRetry`（只接受 FAILED/TIMEOUT），
  会变成"没有 next_retry_time 的 FAILED"，永远不再重试。万一顺序失效，转移会把仍 RUNNING
  的记录收尾并挂上重试时间，退避扫描仍能兜住，不留丢失路径。
- `finish()` 只定向更新终态列并带 `status=RUNNING` 前置条件，不会把并发路径刚写入的
  executor / retry_count / next_retry_time 用旧值覆盖回去。
- `@Scheduled` 默认只有 1 个线程，已配 `spring.task.scheduling.pool.size=4`，
  且重试的同步 HTTP 调用全部下沉到分片线程池，健康检查与僵尸回收不会被拖住。

### 3.7 异步结果上报（Week 4）

```text
调度中心 dispatch（快回包）
  → POST /api/v1/executor/execute
  → 执行器立即回 ACK（受理 / 拒绝），不等待执行结果
  → 执行器在调度线程池里跑处理器（超时由执行器判）
  → 跑完把结果消息发到 results 交换机（persistent + publisher confirm）
  → 调度中心消费 results.q（手动 ACK）
      处理成功 → 落终态（finish 带 status=RUNNING 前置条件）→ 提交事务 → basicAck
      处理失败 → basicNack(requeue=false) → 进死信队列 → 落 tp_dead_letter + 广播告警
```

关键点：

- **dispatch 不再等结果**：长任务不会占住调度线程，手动触发接口也能立刻返回 `RUNNING`。
- **至少一次 + DB 幂等**：结果消息可能重复投递，落终态的 UPDATE 带 `status=RUNNING` 前置条件，
  重复投递命中 0 行，终态只写一次。
- **超时归属执行器**：任务超时由执行器判定并上报；调度中心的僵尸回收只兜底"ACK 之后结果没回来"，
  超过 `task.timeout_seconds + grace` 的 `RUNNING` 标 `TIMEOUT` 并挂上 `next_retry_time`，
  交给退避扫描 / 失败转移接手。
- **broker 不可达时应用照常启动**（懒连接 + `missing-queues-fatal=false`），只是结果回不来，
  由上面那条僵尸回收兜底。

### 3.8 幂等与 fencing 的边界

| 机制 | 实际作用范围 | 不做的事 |
|---|---|---|
| Redisson 锁 + watchdog | 尽量保证同一时刻只有一个 leader | 挡不住网络分区、长 GC 后旧 leader 仍自认为 leader |
| fencing token | **定时触发入口的一次预检**：写库前校验 `fencing_token <= 我的 token`，影响行数为 0 就拒绝本次触发 | 不是写库层的全局强制约束；已进入执行链路的写入不再逐条校验 token |
| `uk_execution_key` 唯一索引 | 同一「计划时间 × 分片（× 执行器）」的重复触发只落一条记录 | 不覆盖手动触发（手动用 UUID，允许重复） |
| `claimForRetry` 原子抢占 | 多实例同时看到同一条失败记录时只有一方重试 | 不跨执行器进程去重 |
| RabbitMQ 至少一次投递 | 结果消息可能重复到达 | **不保证"只投递一次"**；靠落终态的 `status=RUNNING` 前置条件做到幂等 |

**语义是「至少一次 + 业务幂等」，不是「保证不执行两遍」**：执行器侧没有按
`executionId` 做去重。调度中心在执行途中崩溃、HTTP 超时但执行器其实已跑完、
leader 切换窗口等情况下，同一个处理器仍可能被执行两次。幂等键只保证同一个计划时间点
不会重复落库、重复发起，重复执行不重复生效仍要由业务侧自己保证。

## 4. 数据模型

| 表 | 说明 | 关键约束 |
|---|---|---|
| `tp_task` | 任务定义 | 逻辑删除 `deleted`；`idx_status_next` 供时间轮扫描；`next_trigger_time` 持久化排期 |
| `tp_executor` | 执行器注册表 | `uk_app_address` 保证重复启动不产生重复记录 |
| `tp_execution_log` | 执行记录 | `uk_execution_key` 幂等兜底；`next_retry_time` 退避；`fencing_token` / `leader_instance` 记录由哪个 leader 产生 |
| `tp_leader` | leader 状态（单行 `id=1`） | `fencing_token` 单调递增，旧 token 的触发被拒 |
| `tp_dead_letter` | 死信（Week 4） | `uk_dead_execution` 保证同一条执行记录只落一行死信 |

时区统一 `Asia/Shanghai`，时间字段统一 `DATETIME(3)`。
`deploy/sql/01-init.sql` 已含全部表与列；`02-week2-leader.sql`、`03-week3-retry.sql`
只用于老库升级，在全新库上重复执行会报重复表/重复列。

## 5. 后续演进

| 周 | 架构变化 |
|---|---|
| 2 | 时间轮 + Redisson 锁 + fencing token + 重启补偿；`next_trigger_time` 生效 |
| 3 | 分片参数下发、执行器上下线重分配、失败转移、重试/退避、幂等闭环（已落地）；第 3 周评审修复 0.1~1.5 与端到端验收（已落地） |
| 4 | MQ 上报结果、告警 webhook、Micrometer 指标、压测与 CI（**结果异步上报 + 死信告警已落地**，其余待做） |
| 5 | 文档、架构图、演示录屏 |

> 当前进度：第 4 周 Day 1 —— 结果异步上报（RabbitMQ）与死信告警已落地。鉴权、压测、CI 尚未开始。