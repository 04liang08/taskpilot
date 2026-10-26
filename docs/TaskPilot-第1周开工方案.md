# TaskPilot 第 1 周开工方案

> 项目：TaskPilot（分布式任务调度平台）
> 周期：第 1 周 / 共 5 周
> 投入：40 小时（5 天 × 8 小时）
> 开发方式：AI 辅助开发；你负责需求、架构、核心模块验收与调试
> 本周目标：**手动触发端到端跑通 + 极简演示页可用 + 建表/接口/文档齐全**
> 本周不做：时间轮、领导选举、分片、失败转移、重试、MQ、压测（第 2-4 周）

---

## 0. 本周目标与非目标

### 本周必须完成（Definition of Done）
1. Maven 多模块工程可编译、可启动。
2. MySQL 三张核心表建好：`tp_task`、`tp_executor`、`tp_execution_log`。
3. 任务 CRUD、启用/停用、手动触发、执行记录查询可用。
4. 执行器启动后自动注册、定时心跳；调度中心能看到 ONLINE/OFFLINE。
5. 手动触发链路：调度中心 → 执行器 → 返回结果 → 执行记录落库，页面可见。
6. 极简页面：任务列表/新建/触发/启停、执行器列表、执行记录、概览。
7. OpenAPI 文档可访问；README 有启动步骤和 curl 示例。
8. 至少 6 个单元测试通过；`mvn clean verify` 通过。

### 本周明确不做
- 时间轮、cron 自动触发（第 2 周）。
- 领导选举、fencing token（第 2 周）。
- 分片、失败转移、重试/退避、幂等完整闭环（第 3 周）。
- MQ、告警、JMeter 压测（第 4 周）。
- 登录鉴权、多租户、完整前端（不做；页面只用于演示）。

---

## 1. 先定死的技术决策

| 决策点 | 选择 | 原因 |
|---|---|---|
| 服务拆分 | `scheduler` + `executor` + `common` 三模块 | 两段式是调度平台核心；模块少，5 周做得完 |
| 执行器注册 | 自建 `tp_executor` 表 + 心跳 | 调度核心需要精确知道每个执行器地址，分片/故障转移要按地址调用；不把核心逻辑藏在 Nacos 里 |
| Nacos 用途 | 配置中心（可选）；不做执行器成员管理的唯一来源 | 满足 Spring Cloud 技术栈；本地无 Nacos 也能启动 |
| 调度中心 → 执行器 | `RestClient` 按注册地址精确调用 | 分片/失败转移必须精确落到某个执行器；OpenFeign 更适合按服务名负载均衡，不适合“指定实例” |
| OpenFeign | 可选：用于管理端/后续服务间调用，不作为调度主链路 | 避免为了用而用；面试时能讲清“为什么不用 Feign 做精确调度”反而是加分项 |
| cron | Spring `CronExpression`（6 段：秒 分 时 日 月 周） | 不引入 Quartz；第 2 周自己实现时间轮，面试可讲取舍 |
| 手动触发 | 每次生成独立 `execution_key`（UUID） | 手动允许重复触发；定时触发才用确定性幂等键（第 2/3 周） |
| 执行记录 | 先落库再调用，状态 `RUNNING → SUCCESS/FAILED/TIMEOUT` | 出问题可追溯；页面能演示 |
| 页面 | 单页 `index.html` + 原生 JS `fetch`，不引入前端构建 | 极简演示够用；不占用后端学习时间 |
| 数据库 | MySQL 8，`utf8mb4`，`DATETIME(3)`，时区 `Asia/Shanghai` | 与 JD 的 MySQL 要求一致；毫秒级执行记录 |
| 版本 | Java 17 + Spring Boot 3.2.x + Spring Cloud 2023.0.x + Spring Cloud Alibaba 2023.0.x | 以官方兼容表为准；不要混用旧版本 |

> 版本建议：Spring Boot `3.2.5`、Spring Cloud `2023.0.1`、Spring Cloud Alibaba `2023.0.1.0`、Nacos Server `2.3.2`、MyBatis-Plus `3.5.7`、springdoc `2.5.0`、Redisson `3.27.2`。如果下载不到，按官方兼容表就近选，不要跨大版本。

---

## 2. 仓库与 Maven 多模块结构

```text
taskpilot/
├── pom.xml                          # 父 POM：Java 17、依赖管理、插件
├── README.md
├── .gitignore
├── .env.example
├── docs/
│   ├── architecture.md              # 架构图（文字版先写，第 1 周补图）
│   ├── api.md                       # 接口说明（可与 Swagger 互补）
│   └── design-decisions.md          # 本周技术决策记录
├── deploy/
│   ├── docker-compose.yml           # MySQL/Redis/Nacos + 可选 app
│   ├── sql/
│   │   └── 01-init.sql              # 建库建表
│   └── nginx/                       # 预留，不放东西
├── scripts/
│   ├── start-infra.sh               # 启动 MySQL/Redis/Nacos
│   ├── run-scheduler.sh
│   ├── run-executor.sh
│   └── curl-examples.sh
├── taskpilot-common/
│   ├── pom.xml
│   └── src/main/java/com/taskpilot/common/
│       ├── api/ApiResponse.java
│       ├── api/PageResult.java
│       ├── enums/TaskStatus.java
│       ├── enums/TriggerType.java
│       ├── enums/ExecutionStatus.java
│       ├── enums/ExecutorStatus.java
│       ├── exception/BizException.java
│       ├── exception/ErrorCode.java
│       └── util/JsonUtils.java
├── taskpilot-scheduler/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/taskpilot/scheduler/
│       │   ├── TaskPilotSchedulerApplication.java
│       │   ├── config/
│       │   │   ├── MybatisPlusConfig.java
│       │   │   ├── RestClientConfig.java
│       │   │   ├── WebConfig.java
│       │   │   └── OpenApiConfig.java
│       │   ├── controller/
│       │   │   ├── TaskController.java
│       │   │   ├── ExecutorController.java
│       │   │   ├── ExecutionController.java
│       │   │   ├── DashboardController.java
│       │   │   └── CronController.java
│       │   ├── service/
│       │   │   ├── TaskService.java
│       │   │   ├── ExecutorService.java
│       │   │   ├── ExecutionService.java
│       │   │   ├── TriggerService.java
│       │   │   └── DashboardService.java
│       │   ├── client/
│       │   │   └── ExecutorClient.java      # RestClient 封装
│       │   ├── entity/
│       │   │   ├── TaskEntity.java
│       │   │   ├── ExecutorEntity.java
│       │   │   └── ExecutionLogEntity.java
│       │   ├── mapper/
│       │   │   ├── TaskMapper.java
│       │   │   ├── ExecutorMapper.java
│       │   │   └── ExecutionLogMapper.java
│       │   ├── dto/
│       │   │   ├── TaskCreateRequest.java
│       │   │   ├── TaskUpdateRequest.java
│       │   │   ├── TaskQuery.java
│       │   │   ├── ManualTriggerRequest.java
│       │   │   ├── ExecutorRegisterRequest.java
│       │   │   ├── ExecutorHeartbeatRequest.java
│       │   │   └── ExecutionQuery.java
│       │   ├── job/
│       │   │   └── ExecutorHealthCheckJob.java  # 第 1 周临时下线检测
│       │   └── support/
│       │       ├── CronService.java
│       │       └── GlobalExceptionHandler.java
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml
│           └── static/
│               ├── index.html
│               ├── app.js
│               └── style.css
├── taskpilot-executor/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/
│       ├── java/com/taskpilot/executor/
│       │   ├── TaskPilotExecutorApplication.java
│       │   ├── config/
│       │   │   ├── ExecutorProperties.java
│       │   │   ├── RestClientConfig.java
│       │   │   └── ThreadPoolConfig.java
│       │   ├── controller/
│       │   │   └── ExecutorController.java  # register/heartbeat/execute
│       │   ├── service/
│       │   │   ├── ExecutorRegistrationService.java
│       │   │   └── TaskExecuteService.java
│       │   ├── handler/
│       │   │   ├── TaskHandler.java
│       │   │   ├── HandlerRegistry.java
│       │   │   ├── DemoHandler.java
│       │   │   ├── SleepHandler.java
│       │   │   └── HttpHandler.java
│       │   └── dto/
│       │       ├── ExecuteRequest.java
│       │       ├── ExecuteResult.java
│       │       └── RegisterRequest.java
│       └── resources/
│           └── application.yml
└── .github/
    └── workflows/
        └── ci.yml                    # mvn clean verify（第 4 周再补 Docker）
```

### 父 POM 关键点
- `<java.version>17</java.version>`
- `<packaging>pom</packaging>`
- `dependencyManagement` 引入 Spring Boot BOM、Spring Cloud BOM、Spring Cloud Alibaba BOM。
- 插件：`maven-compiler-plugin`（`<release>17</release>`）、`spring-boot-maven-plugin`（各启动模块配置）。

---

## 3. 模块职责与边界

### taskpilot-common
- 只放跨模块 DTO、枚举、错误码、异常、JSON 工具。
- 不依赖 Spring Web；不要放 Mapper/Entity。
- 版本变更要谨慎，避免 scheduler/executor 互相耦合。

### taskpilot-scheduler
- 任务定义管理、cron 校验、执行器注册表、执行记录、手动触发、仪表盘。
- 本周的“调度”只有手动触发；cron 自动触发第 2 周做。
- 对外 REST：`/api/v1/**`；对内调用执行器：`RestClient`。
- 临时 `ExecutorHealthCheckJob` 用 `@Scheduled(fixedDelay = 10000)` 标记超时执行器 OFFLINE；第 2 周被正式失败检测替代。

### taskpilot-executor
- 启动时向 scheduler 注册；每 10 秒心跳。
- 接收 `POST /api/v1/executor/execute`，按 `handlerName` 找处理器执行，返回结果。
- 本周处理器：`demoHandler`（打印参数并返回成功）、`sleepHandler`（演示超时/耗时）、`httpHandler`（可选，调外部 URL）。
- 线程池隔离；后续第 3 周补分片参数和优雅下线。

---

## 4. 数据库设计（MySQL 8）

### 4.1 表关系
- 一个任务 `tp_task` 对应多条执行记录 `tp_execution_log`。
- 一个执行器 `tp_executor` 对应多条执行记录（`executor_id`）。
- 执行记录用 `execution_key` 唯一约束做幂等兜底（第 2/3 周正式使用）。

### 4.2 建表 SQL（`deploy/sql/01-init.sql`）

```sql
CREATE DATABASE IF NOT EXISTS taskpilot
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
USE taskpilot;

CREATE TABLE IF NOT EXISTS tp_task (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
  task_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '任务分组',
  cron_expression VARCHAR(64) NOT NULL COMMENT 'Spring 6 段 cron：秒 分 时 日 月 周',
  trigger_type VARCHAR(16) NOT NULL DEFAULT 'CRON' COMMENT 'CRON/FIXED_RATE/MANUAL',
  handler_name VARCHAR(128) NOT NULL COMMENT '执行器处理器名称',
  handler_param TEXT NULL COMMENT '处理器参数（JSON/字符串）',
  shard_total INT NOT NULL DEFAULT 1 COMMENT '分片总数，第 1 周固定 1',
  timeout_seconds INT NOT NULL DEFAULT 60 COMMENT '单次执行超时秒数',
  retry_max INT NOT NULL DEFAULT 0 COMMENT '最大重试次数',
  retry_interval_seconds INT NOT NULL DEFAULT 10 COMMENT '重试间隔秒数',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  next_trigger_time DATETIME(3) NULL COMMENT '下次触发时间，第 2 周使用',
  last_trigger_time DATETIME(3) NULL COMMENT '上次触发时间',
  description VARCHAR(512) NULL COMMENT '描述',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_task_name (task_name),
  KEY idx_status_next (status, next_trigger_time),
  KEY idx_group (task_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '任务定义';

CREATE TABLE IF NOT EXISTS tp_executor (
  id BIGINT NOT NULL AUTO_INCREMENT,
  app_name VARCHAR(128) NOT NULL COMMENT '执行器应用名',
  executor_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '执行器分组',
  address VARCHAR(128) NOT NULL COMMENT 'host:port',
  status VARCHAR(16) NOT NULL DEFAULT 'ONLINE' COMMENT 'ONLINE/OFFLINE',
  last_heartbeat_time DATETIME(3) NOT NULL COMMENT '最后心跳时间',
  version INT NOT NULL DEFAULT 0,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_address (app_name, address),
  KEY idx_status_heartbeat (status, last_heartbeat_time),
  KEY idx_group (executor_group)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '执行器注册表';

CREATE TABLE IF NOT EXISTS tp_execution_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  execution_key VARCHAR(128) NOT NULL COMMENT '幂等键：定时=taskId:triggerTime:shard；手动=UUID',
  task_id BIGINT NOT NULL,
  task_name VARCHAR(128) NOT NULL,
  trigger_type VARCHAR(16) NOT NULL COMMENT 'CRON/FIXED_RATE/MANUAL',
  trigger_time DATETIME(3) NOT NULL,
  start_time DATETIME(3) NULL,
  end_time DATETIME(3) NULL,
  executor_id BIGINT NULL,
  executor_address VARCHAR(128) NULL,
  shard_index INT NOT NULL DEFAULT 0,
  shard_total INT NOT NULL DEFAULT 1,
  status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
    COMMENT 'RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELED',
  retry_count INT NOT NULL DEFAULT 0,
  duration_ms BIGINT NULL,
  error_message TEXT NULL,
  result_summary VARCHAR(1024) NULL,
  create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_execution_key (execution_key),
  KEY idx_task_trigger (task_id, trigger_time),
  KEY idx_status_create (status, create_time),
  KEY idx_executor (executor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '执行记录';
```

### 4.3 字段与索引说明
- `execution_key` 唯一索引：第 2/3 周防止同一调度周期重复落库；第 1 周手动触发用 UUID，允许重复手动触发。
- `idx_status_next`：第 2 周时间轮/补偿扫描使用；第 1 周不查也没关系。
- `idx_task_trigger`：执行记录按任务+时间查询。
- `idx_status_heartbeat`：第 1 周下线检测；第 3 周失败转移使用。
- 逻辑删除：MyBatis-Plus 全局配置 `deleted`，所有查询自动过滤；`task_name` 不做唯一索引，服务层做重名校验（避免逻辑删除与唯一键冲突）。
- 时区：MySQL 容器 `TZ=Asia/Shanghai`；JDBC `serverTimezone=Asia/Shanghai`；Jackson `time-zone: Asia/Shanghai`。

---

## 5. REST API 清单

统一前缀 `/api/v1`；统一响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

错误码建议：`0` 成功；`40000` 参数错误；`40400` 不存在；`40900` 冲突；`50000` 服务内部错误。HTTP 状态码与错误码同时返回。

### 5.1 任务

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/tasks` | 新建任务 |
| PUT | `/api/v1/tasks/{id}` | 更新任务（名称、cron、handler、参数、超时、重试） |
| DELETE | `/api/v1/tasks/{id}` | 逻辑删除 |
| GET | `/api/v1/tasks` | 分页查询：`page`、`size`、`keyword`、`status`、`group` |
| GET | `/api/v1/tasks/{id}` | 任务详情 |
| POST | `/api/v1/tasks/{id}/enable` | 启用 |
| POST | `/api/v1/tasks/{id}/disable` | 停用 |
| POST | `/api/v1/tasks/{id}/trigger` | 手动触发，返回 `executionId` |

新建请求示例：

```json
{
  "taskName": "demo-every-30s",
  "taskGroup": "DEFAULT",
  "cronExpression": "*/30 * * * * *",
  "handlerName": "demoHandler",
  "handlerParam": "{\"message\":\"hello\"}",
  "shardTotal": 1,
  "timeoutSeconds": 60,
  "retryMax": 0,
  "retryIntervalSeconds": 10,
  "description": "Week1 demo"
}
```

手动触发响应：

```json
{ "code": 0, "message": "ok", "data": { "executionId": 1001 } }
```

### 5.2 执行器

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/executors/register` | 执行器启动注册（执行器调用调度中心） |
| POST | `/api/v1/executors/heartbeat` | 执行器心跳 |
| GET | `/api/v1/executors` | 分页查询：`page`、`size`、`status`、`group` |
| GET | `/api/v1/executors/{id}` | 执行器详情 |

注册请求：

```json
{
  "appName": "taskpilot-executor",
  "executorGroup": "DEFAULT",
  "address": "127.0.0.1:8081"
}
```

心跳请求：

```json
{
  "appName": "taskpilot-executor",
  "address": "127.0.0.1:8081"
}
```

### 5.3 执行记录

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/executions` | 分页：`page`、`size`、`taskId`、`status`、`startTime`、`endTime` |
| GET | `/api/v1/executions/{id}` | 执行详情 |

### 5.4 仪表盘

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/dashboard/summary` | 任务数、启用数、在线执行器数、今日成功/失败、运行中数量 |

响应示例：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "taskCount": 3,
    "enabledTaskCount": 2,
    "onlineExecutorCount": 1,
    "todaySuccess": 5,
    "todayFailed": 1,
    "runningCount": 0
  }
}
```

### 5.5 Cron 校验

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/cron/validate` | 校验表达式并返回未来 5 次触发时间 |

请求：

```json
{ "expression": "*/30 * * * * *" }
```

成功响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "valid": true,
    "nextTimes": [
      "2026-10-26T00:30:00",
      "2026-10-26T00:31:00"
    ]
  }
}
```

非法表达式：HTTP 200 + `valid=false` + `message`，方便页面直接展示；不要抛 500。

### 5.6 执行器侧接口（executor 应用，端口 8081）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/executor/execute` | 调度中心调用，执行任务 |
| GET | `/actuator/health` | 健康检查 |

执行请求：

```json
{
  "executionId": 1001,
  "taskId": 1,
  "handlerName": "demoHandler",
  "handlerParam": "{\"message\":\"hello\"}",
  "shardIndex": 0,
  "shardTotal": 1,
  "timeoutSeconds": 60
}
```

执行响应：

```json
{
  "success": true,
  "resultSummary": "demoHandler done: hello",
  "errorMessage": null,
  "durationMs": 12
}
```

> 第 1 周先同步调用；第 3 周分片/失败转移再考虑异步与超时控制。

---

## 6. 核心链路

### 6.1 执行器注册 / 心跳 / 下线

```text
executor 启动
  → POST /api/v1/executors/register {appName, group, address}
  → scheduler upsert tp_executor，status=ONLINE，last_heartbeat_time=now
  → 每 10s POST /api/v1/executors/heartbeat
  → scheduler 更新 last_heartbeat_time
  → scheduler 每 10s 检查：now - last_heartbeat_time > 30s
      → status=OFFLINE
```

要点：
- 执行器地址必须可被调度中心访问：本地用 `127.0.0.1:8081`；Docker 用 `executor:8081` 或容器 IP。
- 注册用 `(app_name, address)` 唯一键，重复启动不会产生重复记录。
- 第 1 周下线检测只更新状态；第 3 周再做分片重分配。

### 6.2 手动触发时序

```text
页面/curl
  → POST /api/v1/tasks/{id}/trigger
  → scheduler 校验任务存在且 ENABLED
  → 选择在线执行器（第 1 周：轮询/随机）
  → 插入 tp_execution_log：status=RUNNING，execution_key=UUID，trigger_type=MANUAL
  → RestClient POST http://{executor.address}/api/v1/executor/execute
  → executor 按 handlerName 执行
  → scheduler 收到结果：
       success=true  → status=SUCCESS，duration_ms，result_summary
       success=false → status=FAILED，error_message
       超时/连接失败 → status=FAILED，error_message
  → 页面执行记录可见
```

第 1 周边界：
- 不重试；失败就是失败（第 3 周加 retry）。
- 不同步等待太久：`read-timeout` 设为任务超时 + 5s；超时后标记 FAILED。
- 手动触发允许重复，每次 UUID；不要拿 `taskId+时间` 当手动键。

### 6.3 Cron 校验

- 用 `org.springframework.scheduling.support.CronExpression.parse(expression)`。
- Spring 6 段：`秒 分 时 日 月 周`；示例：
  - 每 30 秒：`*/30 * * * * *`
  - 每天 2 点：`0 0 2 * * *`
  - 每周一 9 点：`0 0 9 * * MON`
- 计算未来 5 次：`next(LocalDateTime.now())` 循环；时区固定 `Asia/Shanghai`。
- 第 1 周只做校验/预览；第 2 周时间轮真正触发。

### 6.4 极简页面

- 位置：`taskpilot-scheduler/src/main/resources/static/index.html`，访问 `http://localhost:8080/`。
- 技术：原生 JS + `fetch` + 简单 CSS；不引入构建工具；最多 4 小时。
- Tab：
  1. **概览**：任务数、启用数、在线执行器、今日成功/失败、运行中。
  2. **任务**：列表、搜索、新建/编辑弹窗、启用/停用、手动触发、cron 校验按钮。
  3. **执行器**：列表、状态、最后心跳、地址。
  4. **执行记录**：列表、状态颜色、耗时、错误信息、自动刷新。
- 交互：
  - 新建/编辑用 `<dialog>` 或简单 modal。
  - 触发后 toast 提示 `executionId`，3 秒后刷新执行记录。
  - 概览/执行器/执行记录每 5 秒自动刷新；任务列表手动刷新。
- 不做：登录、权限、复杂图表、前端路由、组件库。

---

## 7. 配置样例

### 7.1 scheduler `application.yml`

```yaml
server:
  port: 8080

spring:
  application:
    name: taskpilot-scheduler
  jackson:
    time-zone: Asia/Shanghai
    date-format: yyyy-MM-dd HH:mm:ss.SSS
  datasource:
    url: jdbc:mysql://localhost:3306/taskpilot?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
    username: taskpilot
    password: taskpilot
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
  cloud:
    nacos:
      config:
        server-addr: localhost:8848
        file-extension: yaml
      discovery:
        server-addr: localhost:8848
  config:
    import: optional:nacos:taskpilot-scheduler.yaml?group=DEFAULT_GROUP&refreshEnabled=true

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0

springdoc:
  swagger-ui:
    path: /swagger-ui.html

taskpilot:
  executor:
    heartbeat-timeout-seconds: 30
    dispatch:
      connect-timeout-ms: 3000
      read-timeout-ms: 65000
    selection: ROUND_ROBIN
```

### 7.2 executor `application.yml`

```yaml
server:
  port: 8081

spring:
  application:
    name: taskpilot-executor
  jackson:
    time-zone: Asia/Shanghai
  cloud:
    nacos:
      config:
        server-addr: localhost:8848
      discovery:
        server-addr: localhost:8848
  config:
    import: optional:nacos:taskpilot-executor.yaml?group=DEFAULT_GROUP&refreshEnabled=true

taskpilot:
  executor:
    app-name: taskpilot-executor
    group: DEFAULT
    address: ${TASKPILOT_EXECUTOR_ADDRESS:127.0.0.1:8081}
    scheduler-base-url: ${TASKPILOT_SCHEDULER_URL:http://127.0.0.1:8080}
    heartbeat-interval-seconds: 10
    handler-scan-package: com.taskpilot.executor.handler
    thread-pool:
      core-size: 8
      max-size: 32
      queue-capacity: 256
```

> Nacos 用 `optional:` 导入；本地没启动 Nacos 也能跑。第 1 周 Nacos 只做配置中心，不做执行器注册表。

---

## 8. Docker Compose（`deploy/docker-compose.yml`）

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: taskpilot-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: taskpilot
      MYSQL_USER: taskpilot
      MYSQL_PASSWORD: taskpilot
      TZ: Asia/Shanghai
    ports:
      - "3306:3306"
    volumes:
      - ./sql:/docker-entrypoint-initdb.d:ro
      - taskpilot-mysql:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-proot"]
      interval: 5s
      timeout: 3s
      retries: 20

  redis:
    image: redis:7-alpine
    container_name: taskpilot-redis
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - taskpilot-redis:/data

  nacos:
    image: nacos/nacos-server:v2.3.2
    container_name: taskpilot-nacos
    environment:
      MODE: standalone
      NACOS_AUTH_ENABLE: "false"
      JVM_XMS: 256m
      JVM_XMX: 512m
    ports:
      - "8848:8848"
      - "9848:9848"
    volumes:
      - taskpilot-nacos:/home/nacos/data

volumes:
  taskpilot-mysql:
  taskpilot-redis:
  taskpilot-nacos:
```

启动基础设施：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis nacos
```

第 1 周建议 **应用用 IDE 启动**，比容器调试快；第 4 周再补 scheduler/executor 的 Dockerfile 和完整 Compose。

---

## 9. 第 1 周每日任务（40 小时）

### Day 1（8h）：工程骨架 + 数据库
- 创建 Git 仓库、父 POM、三个模块。
- 建 `taskpilot-common` 的 DTO/枚举/异常/响应体。
- 写 `deploy/sql/01-init.sql`，启动 MySQL 验证三张表。
- 写 `.gitignore`、`.env.example`、`README` 骨架。
- 提交：`chore: bootstrap multi-module project and schema`。
- 验收：`mvn -q -DskipTests package` 通过；MySQL 表存在。

### Day 2（8h）：任务 CRUD
- 三个 Entity + Mapper + MyBatis-Plus 配置 + 乐观锁/逻辑删除。
- `TaskService`：新建（重名校验）、更新、删除、分页、详情、启用/停用。
- `GlobalExceptionHandler`、`ApiResponse`、参数校验。
- Swagger/OpenAPI 配置，`/swagger-ui.html` 可访问。
- 用 curl 跑通 CRUD。
- 提交：`feat: task CRUD with validation and openapi`。
- 验收：新建/更新/停用/删除/分页都正常；错误返回统一。

### Day 3（8h）：Cron + 仪表盘 + 页面骨架
- `CronService`：校验、未来 5 次触发时间；非法表达式返回 `valid=false`。
- `DashboardService`：任务数、启用数、在线执行器数、今日成功/失败、运行中。
- 页面：概览 + 任务列表 + 新建/编辑弹窗 + 启用/停用 + cron 校验按钮。
- 提交：`feat: cron validation dashboard and task page`。
- 验收：页面能建任务、校验 cron、启停、看到概览数字。

### Day 4（8h）：执行器注册 + 心跳
- `taskpilot-executor` 启动类、配置、线程池。
- `TaskHandler` 接口、`HandlerRegistry`、`DemoHandler`、`SleepHandler`。
- 启动注册 + 每 10 秒心跳；`address` 可配置。
- 调度中心 `ExecutorService`：注册 upsert、心跳更新、分页、下线检测 Job。
- 页面：执行器 Tab；概览在线数。
- 提交：`feat: executor registration heartbeat and handler registry`。
- 验收：启动执行器后页面显示 ONLINE；停掉 30 秒后变 OFFLINE。

### Day 5（8h）：手动触发 + 执行记录 + 收尾
- `TriggerService` / `ExecutionService`：插入 RUNNING 记录 → RestClient 调用执行器 → 更新 SUCCESS/FAILED/超时。
- 执行记录分页/详情；页面触发按钮 + 执行记录 Tab + 自动刷新。
- 端到端手测：建任务 → 触发 → 成功/失败/超时三种情况。
- 单元测试：CronService、TaskService、ExecutionService（Mock ExecutorClient）、ExecutorService。
- README：启动步骤、curl、截图/录屏占位、架构说明。
- 提交：`feat: manual trigger execution log and demo page`、`docs: week1 runbook`。
- 验收：`mvn clean verify` 通过；页面完整演示；API 文档可访问。

---

## 10. 验收标准（Definition of Done）

### 工程
- [ ] `mvn clean verify` 通过（至少 6 个测试）。
- [ ] 三个模块职责清晰，无循环依赖。
- [ ] `.gitignore` 生效，无密码/密钥入库。
- [ ] README 能在 10 分钟内让另一个人跑起来。

### 数据库
- [ ] `tp_task`、`tp_executor`、`tp_execution_log` 三张表创建成功。
- [ ] 唯一键 `uk_app_address`、`uk_execution_key` 生效。
- [ ] 逻辑删除查询过滤正确。
- [ ] 时间字段为 `DATETIME(3)`，时区正确。

### 功能
- [ ] 任务 CRUD、启停、分页、搜索可用。
- [ ] Cron 校验：合法返回未来 5 次；非法返回 `valid=false`，不抛 500。
- [ ] 执行器启动自动注册；心跳更新；30 秒无心跳变 OFFLINE。
- [ ] 手动触发：RUNNING → SUCCESS/FAILED；执行记录有耗时、结果、错误信息。
- [ ] 执行器不存在/离线时，触发返回明确错误，执行记录为 FAILED。
- [ ] 页面：概览/任务/执行器/执行记录四个 Tab 可用；触发后能看到记录。

### 文档
- [ ] Swagger/OpenAPI 可访问。
- [ ] README 有架构图（文字版可）、启动步骤、curl 示例、常见问题。
- [ ] `docs/design-decisions.md` 记录：为什么自建注册表、为什么 RestClient、为什么 6 段 cron、为什么手动触发用 UUID。

---

## 11. 测试与 curl 示例

### 测试清单
1. `CronServiceTest`：合法/非法表达式；未来 5 次时间不为空且递增。
2. `TaskServiceTest`：新建、重名校验、更新、启停、逻辑删除。
3. `ExecutionServiceTest`：触发成功 → SUCCESS；执行器返回失败 → FAILED；执行器超时/连接异常 → FAILED。
4. `ExecutorServiceTest`：注册 upsert、心跳更新、超时标记 OFFLINE。
5. `ExecutionLogMapperTest`：`execution_key` 唯一约束（可用 Testcontainers 或手测）。
6. `DashboardServiceTest`：统计数字正确。

### curl 示例（`scripts/curl-examples.sh`）

```bash
BASE=http://localhost:8080/api/v1

# 1. 新建任务
curl -s -X POST "$BASE/tasks" \
  -H 'Content-Type: application/json' \
  -d '{
    "taskName":"demo-every-30s",
    "taskGroup":"DEFAULT",
    "cronExpression":"*/30 * * * * *",
    "handlerName":"demoHandler",
    "handlerParam":"{\"message\":\"hello\"}",
    "shardTotal":1,
    "timeoutSeconds":60,
    "retryMax":0,
    "retryIntervalSeconds":10,
    "description":"Week1 demo"
  }'

# 2. 任务列表
curl -s "$BASE/tasks?page=1&size=20"

# 3. 校验 cron
curl -s -X POST "$BASE/cron/validate" \
  -H 'Content-Type: application/json' \
  -d '{"expression":"*/30 * * * * *"}'

# 4. 手动触发（把 1 换成任务 id）
curl -s -X POST "$BASE/tasks/1/trigger"

# 5. 执行记录
curl -s "$BASE/executions?page=1&size=20"

# 6. 执行器
curl -s "$BASE/executors?page=1&size=20"

# 7. 概览
curl -s "$BASE/dashboard/summary"
```

---

## 12. 风险与坑

1. **版本兼容**：Spring Boot 3.2 + Spring Cloud Alibaba 必须按官方兼容表；不要用 Spring Boot 2.x 的旧教程。
2. **Nacos 内存**：本地 Nacos 给 256-512MB；启动慢是正常的；用 `optional:nacos:` 防止 Nacos 没起来导致应用启动失败。
3. **Docker 地址**：执行器注册的 `address` 必须是调度中心能访问的地址；本地 `127.0.0.1:8081`，容器 `executor:8081`；不要注册 `localhost` 到容器里。
4. **Cron 段数**：Spring 是 6 段（秒 分 时 日 月 周），不是 Linux 的 5 段；页面示例要写对。
5. **时区**：MySQL、JDBC、Jackson 三处统一 `Asia/Shanghai`；否则执行记录时间会差 8 小时。
6. **逻辑删除 + 唯一键**：`task_name` 不要做唯一索引；用服务层重名校验，避免删了不能重建。
7. **执行记录顺序**：先插 RUNNING 再调用执行器；不要等执行完才插，否则超时/崩溃没有记录。
8. **RestClient 超时**：连接 3s、读超时 = 任务超时 + 5s；不要让 Web 线程无限等。
9. **@Scheduled 只用于心跳/下线检测**：不要拿它当调度核心；第 2 周换时间轮。
10. **安全**：第 1 周不做鉴权，只在本机/内网演示；不要把 8080/8081 暴露公网；第 4 周再考虑简单 token。
11. **页面不要过度投入**：原生 JS 够用；最多 4 小时；后端深度才是简历重点。
12. **AI 辅助**：AI 生成的代码必须逐行读懂；核心模块（注册表、触发、执行记录状态机）你要能改；否则面试会被问穿。

---

## 13. 第 2-5 周预留（本周不做）

- 第 2 周：时间轮 + cron 自动触发 + 领导选举（Redisson + fencing）+ 重启补偿 + 双实例不重复验证。
- 第 3 周：分片 + 失败转移 + 重试/退避 + 超时 + 幂等 + 死信。
- 第 4 周：MQ 结果/告警 + 管理端历史完善 + MySQL 索引/慢查询 + JMeter + GitHub Actions。
- 第 5 周：README/架构图/演示录屏 + 简历 bullets + 模拟面试 + buffer；可选 LLM 失败日志摘要。
- 第 2 周开始，`next_trigger_time`、`idx_status_next` 才会真正用上；本周建表先留好。

---

## 14. Git 提交建议

```text
chore: bootstrap multi-module project and schema
feat: task CRUD with validation and openapi
feat: cron validation dashboard and task page
feat: executor registration heartbeat and handler registry
feat: manual trigger execution log and demo page
test: add cron task execution and executor service tests
docs: week1 runbook and design decisions
```

提交前检查：
- 不提交 `.env`、密码、LLM API Key。
- `target/`、`.idea/`、`logs/` 在 `.gitignore`。
- 每个提交能编译；不要一坨大提交。

---

## 15. 本周结束时要能讲出来的 5 句话

1. 为什么调度中心要自建执行器注册表，而不是只依赖 Nacos？
2. 手动触发为什么先落 RUNNING 记录，再调用执行器？
3. 为什么调度主链路用 RestClient 按地址调用，而不是 OpenFeign 按服务名负载均衡？
4. 执行器心跳超时后，第 1 周只标记 OFFLINE，第 3 周怎么做失败转移？
5. 本周的 execution_key 为什么手动用 UUID，定时才用确定性键？

如果这 5 个问题能脱稿讲清楚，第 1 周就不是“堆代码”，而是为第 2-3 周的分布式核心打好了地基。
