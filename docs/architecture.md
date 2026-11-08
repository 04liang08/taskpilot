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
- 触发：第 1 周仅手动触发；第 2 周接入时间轮 + 领导选举。
- 执行器注册表：注册 / 心跳 / 超时下线；第 3 周扩展为分片与失败转移依据。
- 执行记录：先落 `RUNNING` 再调用执行器，最终更新为 `SUCCESS/FAILED/TIMEOUT`。
- 管理端：REST API + `static/index.html` 极简演示页。

### executor

- 启动时向 scheduler 注册自身地址；每 10 秒心跳。
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