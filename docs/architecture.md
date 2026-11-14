# TaskPilot 架构说明

> 本文描述目标架构与第 1 周落地的范围，随每周迭代更新。

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
- 执行记录：先落 `RUNNING` 再调用执行器，最终更新为 `SUCCESS/FAILED/TIMEOUT`。
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

## 4. 数据模型

| 表 | 说明 | 关键约束 |
|---|---|---|
| `tp_task` | 任务定义 | 逻辑删除 `deleted`；`idx_status_next` 为第 2 周扫描预留 |
| `tp_executor` | 执行器注册表 | `uk_app_address` 保证重复启动不产生重复记录 |
| `tp_execution_log` | 执行记录 | `uk_execution_key` 幂等兜底；`idx_task_trigger` 按任务+时间查询 |

时区统一 `Asia/Shanghai`，时间字段统一 `DATETIME(3)`。

## 5. 后续演进

| 周 | 架构变化 |
|---|---|
| 2 | 时间轮 + Redisson 锁 + fencing token + 重启补偿；`next_trigger_time` 生效 |
| 3 | 分片参数下发、执行器上下线重分配、失败转移、重试/退避、幂等闭环 |
| 4 | MQ 上报结果、告警 webhook、Micrometer 指标、压测与 CI |
| 5 | 文档、架构图、演示录屏 |

> 第 2 周进度：时间轮 + cron 定时触发已落地（Day 1），领导选举与 fencing token 待做。