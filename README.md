# TaskPilot

分布式任务调度平台，采用「调度中心 + 执行器」两段式架构。

当前处于第 1 周 Day 1：已完成多模块骨架、公共模块基础类型、建表 SQL、基础设施编排和文档。任务 CRUD、cron 自动触发、领导选举、分片、失败转移等在后续周实现。

## 模块说明

| 模块 | 职责 |
|---|---|
| `taskpilot-common` | 跨模块共享的统一响应、分页结果、枚举、错误码、异常和 JSON 工具 |
| `taskpilot-scheduler` | 调度中心：任务管理、触发、执行记录、执行器注册表（后续周补充核心调度） |
| `taskpilot-executor` | 执行器：注册、心跳、任务处理器注册与执行（后续周补充分片与优雅下线） |

## 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8.x
- Redis 7、Nacos 2.3.2（第 2 周起使用，可选）
- Docker / Docker Compose（可选，用于一键启动基础设施）

## 快速开始

### 1. 初始化数据库

使用本机 MySQL：

```bash
mysql -u root -p < deploy/sql/01-init.sql
```

或使用 Docker Compose 启动 MySQL、Redis、Nacos：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql redis nacos
```

验证三张核心表：

```sql
USE taskpilot;
SHOW TABLES;
-- tp_task、tp_executor、tp_execution_log
```

### 2. 构建

```bash
mvn clean verify
```

### 3. 启动应用

调度中心（默认 8080）：

```bash
mvn -pl taskpilot-scheduler -am spring-boot:run
```

执行器（默认 8081）：

```bash
mvn -pl taskpilot-executor -am spring-boot:run
```

也可以用 IDE 直接运行 `TaskPilotSchedulerApplication` 和 `TaskPilotExecutorApplication`。

### 4. 配置

复制环境变量模板，按本地环境修改（当前仅作为后续配置参考）：

```bash
cp .env.example .env
```

## 目录结构

```text
taskpilot/
├── pom.xml
├── taskpilot-common/
├── taskpilot-scheduler/
├── taskpilot-executor/
├── deploy/
│   ├── docker-compose.yml
│   └── sql/01-init.sql
├── docs/
│   ├── 项目.md
│   └── TaskPilot-第1周开工方案.md
├── .env.example
└── .gitignore
```

## 文档

- [项目说明](docs/项目.md)
- [第 1 周开工方案](docs/TaskPilot-第1周开工方案.md)

## 安全说明

第 1 周不做登录鉴权，仅限本机或内网演示，不要把 8080/8081 暴露到公网。
