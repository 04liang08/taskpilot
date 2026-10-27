# Day 1 学习笔记：工程骨架 + 数据库

> 对应 Day 1 任务：多模块工程、公共契约、建表 SQL、基础设施、首次提交。
> 本笔记不是操作流水，而是"今天做的每个决定，为什么这么做，替代方案是什么"。

---

## 一、今天到底完成了什么

| 任务 | 结果 | 验收证据 |
|---|---|---|
| Git 仓库 + 父 POM + 三模块 | 完成 | `main` 与 `origin/main` 同步 |
| `taskpilot-common` 公共类型 | 完成 | 9 个类，包名 `com.taskpilot.common` |
| `deploy/sql/01-init.sql` | 完成 | 3 张表 + 2 个唯一键 |
| MySQL 建库建表 | 完成 | `tp_task`、`tp_executor`、`tp_execution_log` 均存在 |
| `.gitignore` / `.env.example` / `README` | 完成 | `.env` 已加入忽略 |
| 构建验收 | 完成 | `mvn -q clean verify` 退出码 0 |
| 首次提交 | 完成 | 已推送 GitHub |

数据库实测结果：

- 唯一键 `uk_execution_key`、`uk_app_address` 均为 `NON_UNIQUE=0`
- 时间字段 `create_time` / `trigger_time` / `start_time` 均为 `DATETIME(3)`
- 字符集 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`
- MySQL 本地时间比 UTC 快 8 小时，符合 `Asia/Shanghai`

---

## 二、四层知识地图

### 第 1 层：工程骨架

父 POM 是 `packaging=pom`，它只做两件事：

1. **聚合模块**：`<modules>` 里的三个模块会被 Maven Reactor 统一构建。
2. **管理版本**：`dependencyManagement` 统一 Boot / Cloud / MyBatis-Plus 版本，子模块不写版本号。

三个模块的依赖方向必须是单向的：

```text
taskpilot-scheduler ──> taskpilot-common
taskpilot-executor  ──> taskpilot-common
taskpilot-common    ──> 无业务模块依赖
```

**关键判断：`taskpilot-common` 不引 `spring-boot-starter-web`。**
一旦引入，scheduler 和 executor 都会被间接绑定到 Servlet 容器；common 也不再是"纯契约"，而是一个隐形的 Web 模块。今天 common 只依赖 Jackson，就是这个原则的体现。

相关文件：

- `pom.xml`
- `taskpilot-common/pom.xml`
- `taskpilot-scheduler/pom.xml`
- `taskpilot-executor/pom.xml`

### 第 2 层：公共契约

| 类型 | 作用 | 设计要点 |
|---|---|---|
| `ApiResponse<T>` | 统一响应体 | `code=0` 表示业务成功；`message` 给人看，`data` 给程序用 |
| `PageResult<T>` | 分页结果 | `total` 用 `long`，避免大表分页溢出 |
| `ErrorCode` | 业务错误码 | 0 / 40000 / 40400 / 40900 / 50000，码和默认文案绑定 |
| `BizException` | 业务异常 | 携带 `ErrorCode`，全局异常处理器只需认这一个类型 |
| `TaskStatus` 等 4 个枚举 | 状态与触发方式 | 数据库存字符串，不存 ordinal |

相关文件：

- `taskpilot-common/src/main/java/com/taskpilot/common/api/ApiResponse.java`
- `taskpilot-common/src/main/java/com/taskpilot/common/api/PageResult.java`
- `taskpilot-common/src/main/java/com/taskpilot/common/exception/ErrorCode.java`
- `taskpilot-common/src/main/java/com/taskpilot/common/exception/BizException.java`
- `taskpilot-common/src/main/java/com/taskpilot/common/enums/`
- `taskpilot-common/src/main/java/com/taskpilot/common/util/JsonUtils.java`

### 第 3 层：数据库（今天的重点）

三张表的关系：

```text
tp_task 1 ──> N tp_execution_log
tp_executor 1 ──> N tp_execution_log（executor_id）
```

今天的表结构不是为了"存下来"，而是为第 2-3 周预留能力：

| 设计 | 服务于哪一周 | 原因 |
|---|---|---|
| `uk_execution_key` 唯一索引 | 第 3 周幂等 | Redis 只是第一道防线，数据库是最后兜底 |
| `execution_key` 规则：定时 `taskId:triggerTime:shard`，手动 `UUID` | 第 2/3 周 | 定时要去重，手动允许重复触发 |
| `task_name` 只建普通索引 | 第 1 周 CRUD | 逻辑删除会让"删了再建同名"撞唯一键 |
| `DATETIME(3)` | 第 2-4 周 | 毫秒级耗时统计、触发时间排序 |
| `idx_status_next` | 第 2 周时间轮补偿 | 按状态 + 下次触发时间扫描 |
| `idx_status_heartbeat` | 第 3 周下线检测 | 按状态 + 最后心跳找超时执行器 |
| `idx_task_trigger` | 第 4 周执行历史 | 按任务 + 时间查执行记录 |
| `version` 乐观锁 | 第 1 周启停 | 防止并发更新覆盖 |

相关文件：`deploy/sql/01-init.sql`

### 第 4 层：基础设施

- MySQL / Redis / Nacos 通过 `deploy/docker-compose.yml` 启动。
- MySQL 的 `TZ=Asia/Shanghai`、JDBC 的 `serverTimezone`、Jackson 的时区，三处必须一致。
- **执行器注册的 `address` 必须是调度中心能访问到的地址。**
  在容器里注册 `127.0.0.1:8081`，调度中心调用时只会调到它自己。

---

## 三、今天最值得记住的 5 个"为什么"

### 1. 为什么用 Boot 3.2.5 + Java 17，而不是更新的版本？

因为 Spring Cloud Alibaba、Nacos、Redisson 这条生态是按 Boot 3.2.x 对齐的。版本矩阵是工程判断，不是"越新越好"。能跑不等于能演进。

### 2. 为什么 HTTP 状态码和业务码要分开？

- HTTP 状态码描述**传输/协议层**结果：200、400、404、409、500。
- 业务码描述**领域层**结果：40000 参数错误、40400 资源不存在、40900 冲突。
- 多个业务错误可以共用一个 HTTP 状态；网关还可能重写状态码。客户端应该按稳定的业务码分支。

### 3. 为什么 `BizException` 要携带 `ErrorCode`？

如果只存一个字符串，全局异常处理器就要靠字符串匹配来猜错误类型，监控也无法按码聚合。携带枚举后，处理器可以直接得到"码 + 默认文案"，再决定 HTTP 状态。

### 4. 为什么 `execution_key` 必须有唯一索引？

第 3 周的链路是：Redis SETNX 抢占 → 落执行记录 → 调用执行器。

- Redis 正常时，SETNX 挡住绝大多数重复。
- Redis 过期、宕机、网络分区时，唯一索引仍然能挡住重复插入。
- 插入时抛 `DuplicateKeyException`，就说明这次触发已经存在，直接当作"已调度"处理。

这就是"至少一次语义 + 数据库兜底"。

### 5. 为什么时间字段用 `DATETIME(3)`？

`DATETIME` 只精确到秒，短任务会显示耗时 0ms，压测数据会失真。`DATETIME(3)` 精确到毫秒，只多占约 2 字节，换来可用的耗时统计和可靠的时间排序。

---

## 四、自测题（先遮住答案）

1. 为什么 `taskpilot-common` 不引 `spring-boot-starter-web`？
2. `mvn -pl taskpilot-scheduler -am` 里的 `-am` 做什么？去掉会怎样？
3. `ApiResponse.code` 和 HTTP 状态码为什么不能合并？
4. `BizException` 为什么携带 `ErrorCode`，而不是只存字符串？
5. `task_name` 为什么不做唯一索引？做了会出什么问题？
6. `execution_key` 唯一索引和 Redis SETNX 各防什么？
7. 为什么时间字段是 `DATETIME(3)`，不是 `DATETIME`？
8. 执行器在容器里，为什么不能注册 `localhost:8081`？

### 参考答案

1. common 被 scheduler 和 executor 共用。引入 Web 会把 Servlet 容器和 Spring MVC 绑到所有使用方，破坏"纯契约模块"的定位，也增加版本冲突面。
2. `-am`（also make）会同时构建当前模块依赖的上游模块。去掉后，如果 `taskpilot-common` 没有 `install` 到本地仓库，单独构建 scheduler 会解析不到依赖。
3. HTTP 状态码是协议语义，业务码是领域语义。一个 HTTP 409 可能对应多种业务冲突；网关还可能统一改写状态码。业务码稳定，客户端才能可靠分支。
4. 让全局异常处理器拿到稳定的错误码和默认文案，避免字符串匹配；同时便于监控按码聚合、后续做国际化。
5. 因为逻辑删除。被删除的行仍然占着唯一索引项，重建同名任务会直接撞键。重名校验放在服务层，只查 `deleted=0` 的记录。
6. Redis SETNX 是性能路径上的第一道锁，挡正常并发；数据库唯一索引是故障路径上的最终兜底，挡 Redis 失效、过期和分区。两者不是二选一。
7. 毫秒精度。`DATETIME` 只到秒，短任务耗时会是 0ms，触发时间排序也会丢精度；`DATETIME(3)` 只多约 2 字节。
8. 容器里的 `localhost` 指容器自己，调度中心调不到。应该注册调度中心可达的地址，例如 Compose 服务名、宿主机 IP 或 `host.docker.internal`，并且做成可配置项。

---

## 五、30 分钟补课

1. **10 分钟：重写契约**
   关掉参考，自己写一遍 `ApiResponse` 和 `ErrorCode`，再对比现有代码，只看差异。
2. **10 分钟：做一次表结构决策**
   不写代码，只写结论：给 `tp_task` 加一个 `owner` 字段，要不要索引？为什么？写三句话。
3. **10 分钟：画手动触发时序**
   画出"页面 → 调度中心 → 执行器 → 执行记录落库"，标出"先落 RUNNING 再调用"的位置。

---

## 六、学习日志（每天 3 行）

```text
日期：
今天做了什么决策：
为什么这样决策：
还有什么替代方案 / 放弃了什么：
```

Day 1 示例：

```text
日期：Day 1
今天做了什么决策：execution_key 建唯一索引，而不是只靠 Redis 去重。
为什么这样决策：Redis 可能过期或不可用，数据库是最后一道幂等防线。
还有什么替代方案：只靠 Redis + 业务侧去重，实现简单，但故障时会重复执行。
```

---

## 七、和 Day 2 的衔接

Day 2 做任务 CRUD 时，会直接用到今天的东西：

- `ApiResponse` / `PageResult`：所有接口的返回格式。
- `ErrorCode` / `BizException`：参数错误、任务不存在、重名冲突。
- `tp_task` 字段：cron 校验、启停、逻辑删除、乐观锁。
- `execution_key` + "先落库再调用"：手动触发链路的地基。

明天开始前，先用 2 分钟回答一个问题：
**"如果任务重名，我的校验应该查哪张表、带什么条件、要不要加锁？"**
答不出来，就回看今天的第 5 题。
