# TaskPilot

分布式任务调度平台：**调度中心（scheduler）+ 执行器（executor）** 两段式架构。

> 当前状态：第 1 周 Day 3 —— 工程骨架、数据库、任务 CRUD、cron 校验、仪表盘与极简演示页已就绪。

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

首次启动 MySQL 会自动执行 `deploy/sql/01-init.sql`，创建 `tp_task`、`tp_executor`、`tp_execution_log` 三张表。

> 如果本机 3306 已被占用，先停止本地 MySQL 服务，或修改 compose 中的端口映射。
> Nacos 启动较慢且当前应用不强依赖它，只起 MySQL 也能开发。

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

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 演示页面：<http://localhost:8080/>（概览 / 任务 / 执行器 / 执行记录四个 Tab）

### 5.5 确认建表

```bash
docker exec -it taskpilot-mysql mysql -utaskpilot -ptaskpilot \
  -e "USE taskpilot; SHOW TABLES;"
```

## 6. API 约定

统一前缀 `/api/v1`，统一响应：

```json
{ "code": 0, "message": "ok", "data": {} }
```

错误码：`0` 成功、`40000` 参数错误、`40400` 不存在、`40900` 冲突、`50001` 执行器不可用、`50000` 服务内部错误。

接口清单与示例见 [第 1 周开工方案 · 第 5 节](docs/TaskPilot-第1周开工方案.md)。

## 7. 当前进度

- [x] Day 1：Maven 多模块、父 POM、三张表 DDL、Docker Compose、文档与脚本骨架
- [x] Day 2：任务 CRUD（Entity/Mapper/Service/Controller + 统一异常 + 参数校验 + OpenAPI）
- [x] Day 3：Cron 校验、仪表盘、极简演示页（概览 + 任务）
- [ ] Day 4：执行器注册、心跳、处理器注册表
- [ ] Day 5：手动触发、执行记录、单元测试、README 收尾

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

## 10. Roadmap

| 周 | 目标 |
|---|---|
| 1 | 手动触发端到端跑通 + 极简演示页 |
| 2 | 时间轮 + cron 自动触发 + 领导选举 + 双实例不重复 |
| 3 | 分片 + 失败转移 + 重试/退避 + 幂等 + 死信 |
| 4 | MQ 结果/告警 + 压测 + CI |
| 5 | 文档、架构图、演示录屏、面试复盘 |