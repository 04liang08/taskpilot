# TaskPilot

分布式任务调度平台：**调度中心（scheduler）+ 执行器（executor）** 两段式架构。

> 当前状态：第 2 周 Day 3 —— 时间轮 + cron 定时触发 + 领导选举（Redisson 锁 + fencing token）已就绪。

## 1. 它解决什么问题

单机 `@Scheduled` / crontab 在集群部署下会遇到四个硬问题：

| 问题 | TaskPilot 的答案 |
|---|---|
| 多实例同时触发，同一任务被执行 N 次 | 领导选举 + 时间轮，集群内只触发一次 |
| 某台机器挂了，任务就丢了 | 心跳检测 → 分片重分配 → 失败转移 → 重试/死信 |
| 任务散落在各机器，无法统一管理 | 任务 CRUD + 启停 + 执行历史 + 告警 |
| 重复投递导致业务重复生效 | `execution_key` 唯一索引 + Redis SETNX，至少一次 + 业务幂等 |

核心目标不是"又一个 CRUD"，而是把 **只触发一次、失败能转移、重复执行不重复生效** 做透。

## 2. 架构

```text
                  ┌──────────────────────────────────┐
管理页面 / curl → │ scheduler-center :8080            │
                  │ 任务管理 / 触发 / 执行记录 / 注册表 │
                  └──────┬───────────────────┬───────┘
                         │                   │
                  MySQL 8 / Redis      RestClient 按地址精确调用
                                             │
                  ┌──────────────────────────▼───────┐
                  │ executor :8081（可水平扩展多实例）  │
                  │ handler 注册 / 执行 / 结果上报      │
                  └──────────────────────────────────┘
```

- 调度中心决定"什么时候触发、在哪个执行器上执行"。
- 执行器只负责"执行处理器、上报结果"，不持有调度逻辑。
- 执行器注册表由调度中心自建（`tp_executor`），Nacos 只作为可选配置中心。

## 3. 模块

| 模块 | 职责 |
|---|---|
| `taskpilot-common` | DTO、枚举、错误码、异常、JSON 工具；不依赖 Spring Web |
| `taskpilot-scheduler` | 任务 CRUD、cron 校验、触发、执行记录、执行器注册表、仪表盘、演示页面 |
| `taskpilot-executor` | 启动注册、心跳、处理器注册表、执行任务、上报结果 |

## 4. 技术栈

- Java 17 + Spring Boot 3.2.5
- Spring Cloud 2023.0.1 + Spring Cloud Alibaba 2023.0.1.0（Nacos 可选）
- MyBatis-Plus 3.5.7 + MySQL 8
- Redis / Redisson（第 2 周起）
- Docker Compose、JMeter、GitHub Actions（第 4 周）
- springdoc-openapi 2.5.0

## 5. 快速开始

### 前置要求

- JDK 17（`java -version` 确认）
- Maven 3.8+
- Docker Desktop（或本地 MySQL 8 / Redis 7）

### 5.1 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis nacos
```

首次启动 MySQL 会自动执行 `deploy/sql/01-init.sql`，创建 `tp_task`、`tp_executor`、`tp_execution_log`、`tp_leader` 四张表。

> 第 2 周 Day 3 起，调度中心需要 Redis 做领导选举；只起 MySQL 的话定时触发不会工作（单机开发可设 `taskpilot.scheduler.election.enabled=false`）。

> 如果本机 3306 已被占用，先停止本地 MySQL 服务，或修改 compose 中的端口映射。
> Nacos 启动较慢且当前应用不强依赖它，只起 MySQL 也能开发。

#### 没有 Docker 时

本机没装 Docker（或 compose 起不来）时，用本地服务开发即可：

```bash
# MySQL 8：本地实例，建表一次即可
mysql -h 127.0.0.1 -P 3306 -u root -p < deploy/sql/01-init.sql
```

- 数据库连接用环境变量覆盖：`TASKPILOT_MYSQL_URL`、`TASKPILOT_MYSQL_USERNAME`、`TASKPILOT_MYSQL_PASSWORD`。
- Redis 从第 2 周（领导选举）开始才需要。Windows 上如果已经装成服务（`Get-Service *redis*` 显示 Running）直接用即可；否则 `redis-server --port 6379` 起一个。
- 两个应用直接用 IDE 运行启动类，不需要打包部署。

### 5.2 构建

```bash
mvn clean verify
```

### 5.3 启动应用

```bash
# 终端 1：调度中心
bash scripts/run-scheduler.sh

# 终端 2：执行器
bash scripts/run-executor.sh
```

也可以直接用 IDE 运行两个启动类：

- `com.taskpilot.scheduler.TaskPilotSchedulerApplication`
- `com.taskpilot.executor.TaskPilotExecutorApplication`

### 5.4 验证

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8081/actuator/health
```

执行器启动后会自动注册，并每 10 秒发送一次心跳：

```bash
# 执行器注册表（页面「执行器」Tab 同源）
curl http://localhost:8080/api/v1/executors

# 执行器已注册的处理器
curl http://localhost:8081/api/v1/executor/handlers
```

停掉执行器约 30 秒后，状态会从 `ONLINE` 变为 `OFFLINE`（下次心跳会自动恢复）。

手动触发一次任务（先确保执行器在线）：

```bash
# 触发任务 1；同步返回本次执行记录，status 为 SUCCESS / FAILED / TIMEOUT
curl -X POST http://localhost:8080/api/v1/tasks/1/trigger

# 查看执行记录（页面「执行记录」Tab 同源）
curl "http://localhost:8080/api/v1/executions?page=1&size=20"

# 按任务/状态过滤
curl "http://localhost:8080/api/v1/executions?page=1&size=20&taskId=1&status=SUCCESS"
```

- 触发前先插入 `RUNNING` 记录，调用结束后更新为 `SUCCESS` / `FAILED` / `TIMEOUT`。
- 没有在线执行器时返回 `50001`，同时保留一条 `FAILED` 执行记录。
- 手动触发每次生成 UUID `execution_key`，允许重复触发。

定时触发（时间轮，1 秒粒度）：

```bash
# 建一个每 30 秒触发的任务；启用后会自己跑
curl -X POST http://localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"taskName":"cron-demo","cronExpression":"*/30 * * * * *","handlerName":"demoHandler"}'

# 执行记录里 triggerType=CRON 的就是定时触发的
curl "http://localhost:8080/api/v1/executions?page=1&size=20"
```

- 时间轮只扫描当前秒的槽位，不做每秒全表扫描；`next_trigger_time` 落在 `tp_task` 里，重启后自动重建。
- 进程停机期间错过的触发默认**不补跑**（`taskpilot.scheduler.misfire-policy=SKIP`）；需要补一次可设 `FIRE_ONCE`，落后超过 `misfire-threshold-seconds` 仍不补。
- 同一任务不允许并发执行：手动触发撞上定时触发会返回 `40900`。
- 任务定义变更靠 leader 每 5 秒与 DB 对账同步到集群，新建任务最多延迟 5 秒生效。

集群模式（同一个库 + 同一个 Redis 启多个 scheduler）：

```bash
# 实例 A
java -jar taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar --server.port=8080
# 实例 B（共用 MySQL 与 Redis）
java -jar taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar --server.port=8090
```

- 只有一个实例会成为 leader 并推时间轮，其余是 follower；leader 身份存在 `tp_leader` 表里。
- leader 崩溃后约 30 秒（锁租约）另一个实例接管，token 递增，旧 leader 的写入会被 fencing 拒绝。
- 不想装 Redis 的单机开发：`taskpilot.scheduler.election.enabled=false`，本实例直接充当 leader。

### 5.5 集群验收脚本

一条命令验证「集群里同一任务只触发一次」+ 故障转移：

```powershell
# 需要先执行 mvn -DskipTests package，并保证 MySQL 已建表、Redis 可用
powershell -ExecutionPolicy Bypass -File scripts/verify-cluster.ps1

# 想跑快一点：5 秒 cron、观察 20 秒
powershell -ExecutionPolicy Bypass -File scripts/verify-cluster.ps1 -CronSeconds 5 -WaitSeconds 20
```

脚本会自己起 executor + 两个 scheduler、建一个 cron 任务、统计触发次数、强杀 leader、验证接管，结束后清理进程；日志在 `.tmp/verify-cluster/`。
断言全部通过时退出码为 0，可直接接进 CI。

参考耗时：10 秒 cron 观察 35 秒 → 触发 3 次（理论 3 次，双实例若都触发会翻倍）；强杀 leader 后 **26 秒**接管，fencing token 从 1 变 2。

相关入口：

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 演示页面：<http://localhost:8080/>（概览 / 任务 / 执行器 / 执行记录四个 Tab）

### 5.6 确认建表

```bash
docker exec -it taskpilot-mysql mysql -utaskpilot -ptaskpilot \
  -e "USE taskpilot; SHOW TABLES;"
```

### 5.7 演示步骤（录屏用）

1. 打开 <http://localhost:8080/>，概览页：任务数、在线执行器、当前 Leader 一览。
2. 新建一个 `*/10 * * * * *` 的任务（处理器 `demoHandler`），观察任务列表的「下次触发」逐秒推进。
3. 10 秒后切到「执行记录」Tab：出现 `triggerType=CRON` 的记录，状态成功。
4. 点任务行的「触发」手动触发一次：toast 提示执行记录 ID 与耗时，执行记录里多一条 `MANUAL`。
5. 跑 `scripts/verify-cluster.ps1`：展示"两个实例只触发一次 + 强杀 leader 后 26 秒接管"。
6. 演示 fencing：把 `tp_leader.fencing_token` 改大，旧 leader 的触发被拒（日志出现 `fencing token 失效`）。

## 6. API 约定

统一前缀 `/api/v1`，统一响应：

```json
{ "code": 0, "message": "ok", "data": {} }
```

错误码：`0` 成功、`40000` 参数错误、`40400` 不存在、`40900` 冲突、`50001` 执行器不可用、`50000` 服务内部错误。

任务与执行记录：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/tasks/{id}/trigger` | 手动触发：校验启用状态、分配执行器（分片任务按策略拆片）、同步执行并返回执行记录 |
| GET | `/api/v1/executions` | 分页查询：`page`、`size`、`taskId`、`status`、`startTime`、`endTime` |
| GET | `/api/v1/executions/{id}` | 执行记录详情 |

触发响应（分片任务会返回多条记录）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "executionId": 9,
    "status": "SUCCESS",
    "shardTotal": 4,
    "durationMs": 5,
    "executions": [{ "id": 9, "shardIndex": 0, "shardTotal": 4, "status": "SUCCESS" }]
  }
}
```

执行状态：`RUNNING`（已落库、调用中）、`SUCCESS`、`FAILED`、`TIMEOUT`、`CANCELED`（第 1 周未使用）。

分片分配策略（`taskpilot.scheduler.shard-allocation`）：`AVERAGE`（默认，均分）、`HASH`（按 taskId+分片号稳定映射）、`BROADCAST`（每个分片发给所有执行器）。

分片之间**并发执行**（独立线程池），实测 4 个分片各执行 1 秒：串行约 4000 ms，并发约 **1290 ms**。分片之间的成败互不影响，汇总状态取"任一分片失败即失败"。

执行器注册表：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/executors/register` | 执行器启动注册，按 `(appName, address)` 幂等 upsert |
| POST | `/api/v1/executors/heartbeat` | 每 10 秒心跳，刷新状态与最后心跳时间 |
| GET | `/api/v1/executors` | 分页查询：`page`、`size`、`status`、`group` |
| GET | `/api/v1/executors/{id}` | 执行器详情 |

执行器侧内部接口（端口 8081）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/executor/execute` | 调度中心调用，按 `handlerName` 执行 |
| GET | `/api/v1/executor/handlers` | 查看已注册处理器 |

内置处理器：`demoHandler`（回显参数）、`sleepHandler`（演示耗时/超时）、`httpHandler`（GET 外部 URL）。

接口清单与示例见 [第 1 周开工方案 · 第 5 节](docs/TaskPilot-第1周开工方案.md)。

## 7. 当前进度

**第 1 周**

- [x] Day 1：Maven 多模块、父 POM、三张表 DDL、Docker Compose、文档与脚本骨架
- [x] Day 2：任务 CRUD（Entity/Mapper/Service/Controller + 统一异常 + 参数校验 + OpenAPI）
- [x] Day 3：Cron 校验、仪表盘、极简演示页（概览 + 任务）
- [x] Day 4：执行器注册、心跳、处理器注册表、执行器页面
- [x] Day 5：手动触发、执行记录、单元测试、README 收尾

**第 2 周**

- [x] Day 1：自实现时间轮（1 秒粒度）+ cron 定时触发 + `next_trigger_time` 推进 + 重启重建
- [x] Day 2：错过触发的补偿策略（`SKIP` / `FIRE_ONCE`）+ 任务变更与时间轮联动
- [x] Day 3：领导选举（Redisson 锁 + fencing token）+ 双实例只触发一次
- [x] Day 4：调度状态可见（概览显示当前 leader）+ 任务变更跨实例同步（定期对账）
- [x] Day 5：集群验收脚本（`scripts/verify-cluster.ps1`）+ 演示步骤

**第 3 周**

- [x] Day 1：执行器多注册中心（修掉单 scheduler 缺口）+ 分片分配策略（平均/哈希/广播）+ 分片触发链路
- [x] Day 2：分片并发执行（独立分片线程池）+ 执行器上下线事件 + 僵尸 RUNNING 记录回收
- [ ] Day 3：失败转移（执行器下线后分片重分配）
- [ ] Day 4：重试/退避 + 幂等闭环
- [ ] Day 5：端到端验收 + 文档

## 8. 文档索引

- [架构说明](docs/architecture.md)
- [技术决策记录](docs/design-decisions.md)
- [第 1 周开工方案（含完整 DDL / API / 每日任务）](docs/TaskPilot-第1周开工方案.md)
- [项目总计划](docs/项目.md)

## 9. 常见问题

1. **Nacos 没启动应用能跑吗？** 能。当前 Nacos 仅规划为可选配置中心，应用启动不依赖它。
2. **时间差 8 小时？** MySQL 容器、JDBC URL、Jackson 三处都固定 `Asia/Shanghai`。
3. **容器里的执行器地址能写 localhost 吗？** 不能。要写调度中心可访问的地址（宿主机用 `127.0.0.1:8081`，容器网络用 `executor:8081`）。
4. **cron 为什么是 6 段？** Spring `CronExpression` 是 `秒 分 时 日 月 周`，不是 Linux crontab 的 5 段。
5. **执行器一直显示 OFFLINE？** 确认调度中心能访问 `taskpilot.executor.address`：本地用 `127.0.0.1:8081`，容器网络用 `executor:8081`；心跳间隔 10 秒，超过 30 秒无心跳才会下线。
6. **手动触发提示“没有可用的在线执行器”？** 先启动 `taskpilot-executor`，等执行器页面出现 `ONLINE`；触发失败也会留下一条 `FAILED` 执行记录。
7. **任务触发提示“任务未启用”？** 手动触发只允许 `ENABLED` 任务，先在任务列表点击「启用」。
8. **定时任务没触发？** 看调度中心日志里的 `time wheel loaded: N task(s)`：N 为 0 说明没有启用中的 cron 任务；再看任务列表的「下次触发」是否正常推进。cron 必须是 6 段（秒 分 时 日 月 周）。
9. **多实例下定时任务不触发了？** 确认 Redis 可达（`redis-cli ping`），且 `tp_leader` 里有 leader 记录；日志里应出现 `became leader`。Redis 不可用时实例拿不到 leader 身份，会一直不触发（这是有意的失败关闭）。
10. **leader 换了个实例后执行记录里的 leader 变了？** 正常。`tp_execution_log.leader_instance` / `fencing_token` 记录了每次触发由哪个 leader 产生。

## 10. 已知缺口

| 缺口 | 影响 | 计划 |
|---|---|---|
| ~~分片串行执行~~ | **已修复**：分片并发执行（4 分片各 1 秒：串行 4000ms → 并发 1290ms） | — |
| 执行器下线后分片不重分配 | 下线执行器上的分片不会自动转移到别的执行器 | Week 3 Day 3：分片重平衡 |
| 定时触发是同步的（阻塞 Web 线程） | 长任务会占住请求线程 | Week 3：异步化 + 执行结果回调 |
| 没有重试与退避 | 执行失败就是失败 | Week 3 Day 4：重试/退避 + 死信 |
| 没有幂等闭环 | 定时触发的 `execution_key` 还是 UUID，没有去重 | Week 3 Day 4：确定性键 + 唯一索引 |
| 执行器接口无鉴权 | 仅限内网演示 | Week 4：简单 token |
| 没有压测数据 | 简历里不能写吞吐/延迟数字 | Week 4：JMeter 实测 |

## 11. Roadmap

| 周 | 目标 |
|---|---|
| 1 | 手动触发端到端跑通 + 极简演示页 |
| 2 | 时间轮 + cron 自动触发 + 领导选举 + 双实例不重复 |
| 3 | 分片 + 失败转移 + 重试/退避 + 幂等 + 死信 |
| 4 | MQ 结果/告警 + 压测 + CI |
| 5 | 文档、架构图、演示录屏、面试复盘 |