# TaskPilot

分布式任务调度平台：**调度中心（scheduler）+ 执行器（executor）** 两段式架构。

> 当前状态：第 4 周 Day 1 —— 执行结果改走 RabbitMQ 异步上报（调度中心只拿 ACK，结果经 MQ 回传），
> 手动触发统一返回 `RUNNING` 由页面轮询；重试耗尽与毒消息落死信表并广播告警。第 1~3 周能力（分片并发、失败转移、退避重试、幂等）均已就绪。

## 1. 它解决什么问题

单机 `@Scheduled` / crontab 在集群部署下会遇到四个硬问题：

| 问题 | TaskPilot 的答案 |
|---|---|
| 多实例同时触发，同一任务被执行 N 次 | 领导选举 + 时间轮，集群内只触发一次 |
| 某台机器挂了，任务就丢了 | 心跳检测 → 分片重分配 → 失败转移 → 重试/死信 |
| 任务散落在各机器，无法统一管理 | 任务 CRUD + 启停 + 执行历史 + 告警 |
| 重复投递导致业务重复生效 | `execution_key` 唯一索引（定时触发）+ 业务侧幂等，语义是「至少一次」 |

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
- RabbitMQ 3（第 4 周起：结果异步上报 + 死信告警）
- Docker Compose、JMeter、GitHub Actions（第 4 周）
- springdoc-openapi 2.5.0

## 5. 快速开始

### 前置要求

- JDK 17（`java -version` 确认）
- Maven 3.8+
- Docker Desktop（或本地 MySQL 8 / Redis 7）

### 5.1 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis nacos rabbitmq
```

首次启动 MySQL 会自动执行 `deploy/sql/01-init.sql`，创建 `tp_task`、`tp_executor`、`tp_execution_log`、`tp_leader` 四张表。

> `01-init.sql` 已包含 Week 2/3 的全部内容（`tp_leader`、fencing 列、`next_retry_time`）。`02-week2-leader.sql`、`03-week3-retry.sql` 只用于**老库升级**，在全新库上再执行会报重复表 / 重复列。

> 第 2 周 Day 3 起，调度中心需要 Redis 做领导选举；只起 MySQL 的话定时触发不会工作（单机开发可设 `taskpilot.scheduler.election.enabled=false`）。

> 第 4 周起，执行结果改走 RabbitMQ 异步上报（5672，管理界面 <http://localhost:15672>，账号 taskpilot/taskpilot）。**broker 不可达时应用照常启动**（懒连接），但结果回不来——调度中心的僵尸回收会把这类记录兜底标成 TIMEOUT。第 1~3 周的核心功能（手动触发 + 定时触发）在没有 RabbitMQ 时仍然可用，只是执行结果不再经 MQ 回传。

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

> 做端到端验证前，务必确认构建产出的是**最新**的 jar：
> `mvn -DskipTests package` 仍会编译测试代码，**测试编译失败时不会更新 jar**，
> 容易导致"改了代码但验证的还是旧包"。稳妥做法是直接 `mvn clean verify`，
> 并用 `jar tf target/*.jar | grep <新类名>` 确认新类确实打进去了。

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

- 触发前先插入 `RUNNING` 记录；**第 4 周起派发立即返回**（接口响应里状态是 `RUNNING`），
  执行器跑完把结果经 RabbitMQ 回传后才更新为 `SUCCESS` / `FAILED` / `TIMEOUT`，
  轮询 `GET /api/v1/executions/{id}` 到终态即可。
- 没有在线执行器时返回 `50001`，同时保留一条 `FAILED` 执行记录。
- 手动触发每次生成 UUID `execution_key`，允许重复触发。
- 同一任务不允许并发执行：手动触发撞上定时触发（或上一次还没跑完）会返回 `40900`。

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
- leader 被强杀后约 30 秒（Redisson watchdog 的默认锁超时）另一个实例接管，token 递增，旧 leader 的写入会被 fencing 拒绝；正常关闭走 `@PreDestroy` 主动释放锁，其他实例可立刻接管。
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

第 3 周 Day 5 的六场景验收另见独立脚本（同样按端口定位进程、日志放 `.tmp/`、退出码 0/1 可接 CI）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1
# 本机 MySQL 不在默认 3306 端口时
powershell -ExecutionPolicy Bypass -File scripts/verify-week3.ps1 -MysqlPort 3307
```

双 scheduler + 双 executor，覆盖：① 分片并发计时；② 杀执行器后失败转移成功且记录指向新执行器；③ 退避重试时序（5s→10s）；④ 同一计划时间重复触发被幂等去重；⑤ 广播任务每台执行器都执行；⑥ 改 cron 后触发频率不翻倍。实测数字写在 `.tmp/verify-week3/results.json`。

参考实测（本机双 scheduler + 双 executor，两次完整运行）：4 分片各 1 秒 → 串行估算 4000 ms、并发 **1266~1268 ms（3.15~3.16x）**；退避间隔 **5.1s / 10.3s**；杀执行器到失败转移重跑成功 **19~22s**；广播 2 分片 × 2 执行器，每个计划时间 4 条记录；改 cron 后 22 秒内新增 2~3 次（10s 周期理论 2 次，未翻倍）。

第 4 周的异步链路另有一个验收脚本（同样按端口定位进程、日志放 `.tmp/`、退出码 0/1 可接 CI）：

```powershell
# 需要先起 RabbitMQ：docker compose -f deploy/docker-compose.yml up -d rabbitmq
powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1
# 本机 MySQL 不在默认 3306 端口时
powershell -ExecutionPolicy Bypass -File scripts/verify-week4.ps1 -MysqlPort 3307
```

双 scheduler + 双 executor，覆盖：① cron 长任务（sleep 15s）RUNNING → SUCCESS 经 MQ 落库；② 手动触发 < 2s 返回 `RUNNING`、轮询到终态；③ 杀执行器后异步链路 failover 成功且记录指向新执行器；④ 重试预算耗尽 → `tp_dead_letter` 有行 + 告警日志；⑤ 重复投递同一条结果消息 → 终态只写一次。实测数字写在 `.tmp/verify-week4/results.json`。

参考实测（本机双 scheduler + 双 executor + RabbitMQ 3.13）：长任务（sleep 15s）执行耗时 **15009 ms**，执行期间调度中心健康检查 **10 ms** 响应；手动触发接口 **52 ms** 返回 `RUNNING`，轮询到 SUCCESS 耗时 15006 ms；杀执行器到异步链路 failover 重跑成功 **23 s**，记录 executor 已改指向存活执行器、`retry_count=1`；重试预算耗尽后 `tp_dead_letter` 落 1 行，概览死信数同步为 1。

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
4. 点任务行的「触发」手动触发一次：toast 提示「已提交，执行记录 ID=x」，执行记录里多一条 `MANUAL`（先落 `RUNNING`，几秒后由执行器经 RabbitMQ 回传结果变成 `SUCCESS`）。
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
| POST | `/api/v1/tasks/{id}/trigger` | 手动触发：校验启用状态、分配执行器（分片任务按策略拆片）、**异步派发**并立即返回 `RUNNING` 的执行记录 |
| GET | `/api/v1/executions` | 分页查询：`page`、`size`、`taskId`、`status`、`startTime`、`endTime` |
| GET | `/api/v1/executions/{id}` | 执行记录详情 |

触发响应（分片任务会返回多条记录；第 4 周起触发是异步的，状态先是 `RUNNING`）：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "executionId": 9,
    "status": "RUNNING",
    "shardTotal": 4,
    "durationMs": null,
    "executions": [{ "id": 9, "shardIndex": 0, "shardTotal": 4, "status": "RUNNING" }]
  }
}
```

执行状态：`RUNNING`（已落库、调用中）、`SUCCESS`、`FAILED`、`TIMEOUT`、`CANCELED`（第 1 周未使用）。

分片分配策略（`taskpilot.scheduler.shard-allocation`）：`AVERAGE`（默认，均分）、`HASH`（按 taskId+分片号稳定映射）、`BROADCAST`（每个分片发给所有执行器）。

分片之间**并发执行**（独立线程池），实测 4 个分片各执行 1 秒：串行约 4000 ms，并发约 **1290 ms**。分片之间的成败互不影响，汇总状态取"任一分片失败即失败"。

失败转移：分片失败且原执行器不可用时，把该分片重新分配给其他在线执行器并重试，**复用同一条执行记录**（`retry_count + 1`）。实测杀掉承载某分片的执行器后，约 30 秒（心跳超时）判定下线，该分片被转移到另一台执行器重试成功：`FAILED` → `SUCCESS`，`retry_count=1`。

重试与幂等：

- **退避重试**：执行失败后按 `retry_interval_seconds × 2^已重试次数` 重试（封顶 5 分钟），次数受任务 `retry_max` 限制；重试状态落 `next_retry_time`，调度中心重启后不丢。
- **幂等**：定时触发的 `execution_key` 是确定性的 `taskId:计划触发时间:分片号`（如 `3:20261115161500:0`），同一计划时间点的重复触发会被 `uk_execution_key` 挡下；广播策略下一个分片有多台目标执行器，键追加 `:执行器Id`（**单目标分片的键保持不变**）；手动触发用 UUID，允许重复。
- 分工：原执行器不可用 → 换机重试（立即）；原执行器还在（处理器抛异常）→ 原地退避重试。
- **边界（别误解）**：fencing 是定时触发入口的一次预检，不是写库层的全局强制；执行器侧没有按 `executionId` 去重。整体语义是**至少一次 + 业务幂等**，不是"保证不执行两遍"。
- **第 4 周起**：执行器只回 ACK，结果经 RabbitMQ 异步回传（`taskpilot.execution.results`）。
  结果消息是**至少一次**投递，重复投递靠落终态的 `status=RUNNING` 前置条件做到幂等（终态只写一次）。
  重试预算耗尽或结果消息成了毒消息 → 落 `tp_dead_letter`（按 `execution_id` 唯一）+ 广播告警到 `taskpilot.alerts`。

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
| POST | `/api/v1/executor/execute` | 调度中心调用，按 `handlerName` 执行；**只回 ACK（受理与否）**，结果经 RabbitMQ 回传 |
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
- [x] Day 3：失败转移（执行器下线后分片重新分配并重试，复用原记录 + 原子抢占）
- [x] Day 4：确定性幂等键（`taskId:计划时间:分片号` + 唯一索引兜底）+ 指数退避重试
- [x] Day 5：端到端验收（`scripts/verify-week3.ps1`）+ 文档 + 评审缺陷修复（0.1~1.5）

**第 4 周**

- [x] Day 1：结果异步上报（RabbitMQ：ACK + 结果消息 + DLX）+ 手动触发统一异步 + 死信表与告警广播 + `scripts/verify-week4.ps1`
- [ ] Day 2~5：鉴权、压测（JMeter）、CI、指标（Micrometer）

## 8. 文档索引

- [架构说明](docs/architecture.md)
- [技术决策记录](docs/design-decisions.md)
- [Backlog / Week 4 起点](docs/backlog.md)
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
| ~~执行器下线后分片不重分配~~ | **已修复**：失败转移把分片换到其他执行器重试（复用原记录，`retry_count+1`） | — |
| ~~没有退避重试~~ | **已修复**：指数退避（5s → 10s → …），重试状态落 `next_retry_time`，重启不丢 | — |
| ~~没有幂等闭环~~ | **已修复**：定时触发用确定性键 + `uk_execution_key` 兜底 | — |
| ~~时间轮跨槽位残留 / 桶非线程安全~~ | **已修复（0.1）**：重排先清旧条目、公开方法加同步；改 cron 后不再频率翻倍 | — |
| ~~BROADCAST 定时触发被幂等键吞掉~~ | **已修复（0.2）**：多目标分片键追加执行器维度，单目标分片键保持不变 | — |
| ~~重试同步阻塞单线程调度器~~ | **已修复（0.3）**：dispatch 下沉分片线程池、`@Scheduled` 池 4 线程；池满时放回 `next_retry_time` | — |
| ~~下线事件监听器无序 / RUNNING 记录丢失~~ | **已修复（1.1）**：`@Order` 保证收尾先于转移、收尾挂 `next_retry_time` 兜底、预算耗尽落终态 | — |
| ~~failover 后记录仍指向旧执行器~~ | **已修复（1.2）**：claim 的同一条 UPDATE 把 executor 列改指向目标执行器 | — |
| ~~心跳恢复窗口内误发下线事件~~ | **已修复（1.3）**：逐行条件更新，affected = 1 才发事件 | — |
| ~~leader 优雅让位失效 / finish 整实体写回~~ | **已修复（1.4）**：关闭时按归属 `forceUnlock()`；`finish` 定向列更新并带状态前置条件 | — |
| ~~createFailed 标错 triggerType / MANUAL 被转移 / 死配置~~ | **已修复（1.5）**：传入真实 triggerType、MANUAL 不参与转移、删除 `lease-seconds` | — |
| ~~失败转移有延迟~~ | 最坏要等 30 秒心跳超时才判定下线（双实例实测接管 19~22s） | Week 4：执行器主动上报下线 / 调小心跳超时 |
| ~~没有死信与告警~~ | **已修复（4.5）**：重试耗尽与毒消息落 `tp_dead_letter`（按 `execution_id` 幂等），并广播到 `taskpilot.alerts` fanout，webhook 可选 | — |
| ~~结果上报仍是同步 HTTP 回包~~ | **已修复（4.2/4.3）**：执行器只回 ACK，结果经 RabbitMQ 异步回传；手动触发统一返回 `RUNNING`，页面轮询终态 | — |
| 执行器结果回传依赖 RabbitMQ | broker 不可达时应用仍能启动，但结果回不来，要等僵尸回收（`timeout + grace`）兜底标 TIMEOUT | ADR-019 已如实记录；保持现状 |
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

> Week 3 已收尾。评审遗留项（不阻塞合并）与 Week 4 起点见 [Backlog](docs/backlog.md)。