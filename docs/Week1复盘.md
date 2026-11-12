# TaskPilot 第 1 周复盘

> 复盘时间：2026-11-12（Day 5 提交 `db9604d` 之后两天）
> 范围：第 1 周（Day 1 ~ Day 5，5 次提交），对照 `docs/TaskPilot-第1周开工方案.md` 的 Definition of Done。
> 用途：面试复盘素材 + 第 2 周开工前的现状确认。

---

## 1. 一句话结论

第 1 周目标「手动触发端到端跑通 + 极简演示页可用 + 建表/接口/文档齐全」**已完成并有实测数据支撑**：
手动触发链路（调度中心 → 执行器 → 结果 → 执行记录落库 → 页面可见）在真实 MySQL 上跑通，成功/失败/超时/无执行器/连接被拒 5 种分支全部验证；
核心难点（注册表、心跳、触发编排、执行记录状态机）代码量可控、全部逐行可讲。

**尚未开始、也是项目真正的分水岭**：多实例下的「只触发一次」（领导选举 + fencing token）。
第 1 周做的所有事情，都是为了让第 2 周能在**正确的前提**上加时间轮和选主。

---

## 2. 交付对照

### 2.1 方案 DoD 逐条核对

| 方案验收项 | 状态 | 证据 |
|---|---|---|
| `mvn clean verify` 通过（≥6 测试） | ✅ | BUILD SUCCESS，23 个测试全绿（7 个测试类） |
| 三模块职责清晰、无循环依赖 | ✅ | common 不依赖 Spring Web；executor 不依赖 scheduler |
| `.gitignore` 生效、无密码入库 | ✅ | `.env`/`*.key`/`target/` 已忽略，口令走环境变量 |
| README 能让另一个人 10 分钟跑起来 | ⚠️ 部分 | 步骤完整，但**环境前提写的是 Docker**，而本机 Docker 守护进程未运行（见 §5） |
| 三张表创建成功 | ✅ | `tp_task` / `tp_executor` / `tp_execution_log` |
| `uk_app_address`、`uk_execution_key` 生效 | ✅ | 重复注册 id 不变；执行记录 `execution_key` 全为 UUID，实测 `COUNT(*) = COUNT(DISTINCT execution_key)` |
| 逻辑删除过滤正确 | ✅ | MyBatis-Plus `@TableLogic` |
| 时间字段 `DATETIME(3)`、时区正确 | ✅ | MySQL/JDBC/Jackson 三处 `Asia/Shanghai` |
| 任务 CRUD、启停、分页、搜索 | ✅ | 页面 + curl |
| cron 校验合法返回 5 次、非法返回 `valid=false` | ✅ | `CronServiceTest` + 页面预览 |
| 执行器自动注册、心跳刷新、30 秒下线 | ✅ | 见 §3 实测 |
| 手动触发 RUNNING → SUCCESS/FAILED | ✅ | 含耗时、结果摘要、错误信息 |
| 无执行器时明确报错 + 记录 FAILED | ✅ | HTTP 503 `50001`，同时落一条 FAILED 记录 |
| 页面四个 Tab 可用、触发后可见记录 | ✅ | Playwright 验证，无 JS 错误 |
| Swagger/OpenAPI 可访问 | ✅ | `/swagger-ui.html`、`/v3/api-docs` |
| README 有架构图、启动步骤、curl、FAQ | ✅ | 文字版架构图 + 10 条 curl + 7 条 FAQ |
| `docs/design-decisions.md` 记录 4 条关键决策 | ✅ | ADR-001 ~ ADR-007 |

### 2.2 五次提交

| Commit | 日期 | 内容 |
|---|---|---|
| `725f8bf` | 11-08 | `chore: bootstrap multi-module project and schema` |
| `7c7e358` | 11-08 | `feat: task CRUD with validation and openapi` |
| `c0243c2` | 11-08 | `feat: cron validation dashboard and task page` |
| `d861a2c` | 11-10 | `feat: executor registration heartbeat and handler registry` |
| `db9604d` | 11-10 | `feat: manual trigger execution log and demo page` |

代码规模：68 个 Java 文件（main 61 / test 7），scheduler + executor 共 18 个 HTTP 端点，3 张表，4 个 Tab 的演示页。

---

## 3. 实测数据

> 全部来自真实环境（临时 MySQL 8.1 实例 + 本机 JVM 启动 scheduler/executor），不是推算值。环境限制见 §5.1。

### 3.1 执行器注册与心跳

| 观测项 | 实测结果 |
|---|---|
| executor 启动到注册成功 | ≤ 3 秒（`ApplicationReadyEvent` 触发注册） |
| 心跳间隔 | 10 秒，`last_heartbeat_time` 每次刷新 |
| 停掉 executor 后状态变化 | 约 35 秒转 `OFFLINE`（心跳超时 30 秒 + 健康检查每 10 秒扫一次） |
| 概览「在线执行器」计数 | 1 → 0，与注册表一致 |
| 重复注册（幂等） | id 不变，`total` 仍为 1（`uk_app_address`） |
| 停机后重新心跳 | `OFFLINE` → `ONLINE` 自动恢复 |

### 3.2 手动触发各分支

| 场景 | HTTP | 执行记录 status | 耗时 | 错误信息 |
|---|---|---|---|---|
| `demoHandler` 正常 | 200 | SUCCESS | 4 ms | — |
| 未知处理器 | 200 | FAILED | 1 ms | `未注册的处理器: missingHandler…` |
| `sleepHandler` 超时（任务超时 1s） | 200 | TIMEOUT | 1010 ms | `执行超时（1s）` |
| 任务已停用 | 400 | 不产生记录 | — | `任务未启用，无法触发` |
| 无在线执行器 | 503 | FAILED（executor_id 为空） | 0 ms | `没有可用的在线执行器` |
| 执行器被强杀后触发（注册表仍 ONLINE） | 200 | FAILED | 29 ms | `调用执行器失败: Connection refused` |

要点：先落 `RUNNING` 再调用执行器，所以**超时、连接被拒、进程崩溃都能留下记录**；无执行器这一分支同样落一条 FAILED 记录，便于页面追溯。

### 3.3 页面

- 概览 / 任务 / 执行器 / 执行记录 四个 Tab，5 秒自动刷新
- 任务行内「触发」按钮 → toast 返回 `执行记录 #id：成功（4 ms）`
- 执行记录表：状态色标（成功绿/失败红/超时橙）、耗时、结果摘要或错误信息、任务 ID 与状态过滤、分页
- Playwright 无头浏览器实测：无 pageerror，过滤条件生效

---

## 4. 技术决策回顾（面试可讲的取舍）

| 决策 | 一句话理由 | 对应 ADR |
|---|---|---|
| 自建 `tp_executor` 注册表，不用 Nacos 做成员管理 | 调度需要**精确地址**（分片/失败转移都按地址定向调用），核心逻辑不能藏在注册中心里 | ADR-001 |
| 用 `RestClient` 按地址直连，不用 OpenFeign | Feign 是「随便挑一个实例」的负载均衡语义，调度要的是「指定执行器执行指定分片」 | ADR-002 |
| cron 用 Spring `CronExpression`（6 段） | 第 2 周自己实现时间轮，Quartz 的调度模型与职责重叠且过重 | ADR-003 |
| 手动触发用 UUID `execution_key`，定时才用确定性键 | 手动语义就是「人点一次」，不该被幂等键拦截；定时才是重复执行高发场景 | ADR-004 |
| 先落 RUNNING 再调用执行器 | 否则超时/崩溃就没有记录，问题不可追溯 | 方案 §12.7 |
| 不引 MQ，第 1 周同步调用 | 先把链路跑通，第 3 周异步化时再评估 MQ | ADR-007 |

面试可延伸的深度问题：**为什么锁 + fencing token 还不够**（锁过期后旧 leader 仍在写）、**为什么幂等键要「确定性键 + 唯一索引 + Redis SETNX」三层**、**为什么失败要先记录再转移**。

---

## 5. 缺口与风险（诚实清单）

### 5.1 环境类（不属于代码，但影响后续节奏）

1. **Docker 守护进程未运行** → `docker compose up` 起不了 MySQL/Redis/Nacos。README 的启动步骤默认 Docker，本机实际是用临时 MySQL 8.1 实例（端口 3307）做的 E2E。
   - 第 1 周绕过方式：临时实例 + `mysqld --initialize-insecure`，测完即删。
   - 第 2 周需要 Redis（选主）：本机有 `D:\software\redis-server.exe`，可直接起本地 Redis，**不必等 Docker**。
2. README 需要补一段「无 Docker 时的本地启动方式」，否则换个人跑不起来（DoD 那一条目前只算部分达成）。

### 5.2 代码级待清理（都是小问题，但会被 review 问到）

| 问题 | 位置 | 说明 |
|---|---|---|
| 死配置 | `taskpilot-executor/application.yml` 的 `handler-scan-package` | `HandlerRegistry` 实际靠构造器注入收集 Bean，这个配置从未被读取 |
| 死配置 | `taskpilot-scheduler/application.yml` 的 `selection: ROUND_ROBIN` | `TriggerService` 里是写死的进程内计数器，配置项没被读取 |
| 字段未使用 | `tp_task.last_trigger_time` | 建表预留了但从未写入，手动触发也没更新 |
| 枚举未使用 | `ExecutionStatus.CANCELED` | 第 1 周用不到，已在 README 标注 |
| 同步阻塞 | `TriggerService.trigger` | 任务执行期间占用 Web 线程；长任务体验差，第 3 周异步化时处理 |

### 5.3 功能缺口（按重要度）

1. **多实例重复触发**（第 2 周核心）：当前 `TriggerService` 直接被 HTTP 调用，两个 scheduler 实例会各触发一次。
2. **定时触发**：只有手动触发，cron 字段目前只做校验与预览。
3. **失败转移 / 重试 / 分片 / 幂等闭环**：全未做。
4. **可观测**：无 Micrometer 指标、无告警、无压测数据（项目.md 明确要求数字必须 JMeter 实测）。
5. **无鉴权**：方案内明确第 1 周不做，仅限内网演示。

---

## 6. 面试问答准备（8 题）

1. **为什么不用 Nacos 管执行器？** → 需要精确地址做定向调用；自建表还能记录状态与心跳，便于超时判定和排查。
2. **为什么不用 OpenFeign？** → Feign 的负载均衡语义和调度的定向语义冲突；少一层也更易定位超时。
3. **cron 为什么 6 段？** → Spring `CronExpression` 是 `秒 分 时 日 月 周`；6 段便于秒级精度，不用引 Quartz。
4. **手动触发为什么用 UUID？** → 手动允许重复；定时才用 `taskId:触发时间:分片号` 这类确定性键。
5. **为什么先插记录再执行？** → 超时、连接被拒、进程崩溃都要有痕迹可查。
6. **幂等怎么做？** → 第 3 周：确定性 `execution_key` + 唯一索引兜底 + Redis SETNX 前置拦无效写；业务侧仍需幂等（至少一次语义）。
7. **集群里怎么保证只触发一次？** → 第 2 周：Redisson 锁选主 + fencing token 让过期 leader 的写请求失效；再补 DB 持久化与重启补偿。
8. **这个项目你具体做了什么？** → 按真实情况答：需求与架构由你定，AI 辅助编码，你负责逐行读懂、验收和调试；领导选举、时间轮、分片重平衡、幂等落库这四块你要亲手改。

---

## 7. 简历 bullets 草稿（只写已完成 + 实测）

- **TaskPilot（分布式任务调度平台）｜Java 后端｜AI 辅助开发**：设计并实现调度中心 + 执行器两段式架构，Spring Boot 3.2 / MyBatis-Plus / MySQL 8。
- 自建执行器注册表（注册/心跳/超时下线），重复注册幂等；心跳 10 秒、30 秒无心跳自动下线，状态恢复实测 < 35 秒。
- 实现手动触发端到端链路：先落 `RUNNING` 执行记录再调用执行器，结果映射为 SUCCESS/FAILED/TIMEOUT，失败分支（未知处理器、超时、连接被拒、无在线执行器）均实测落库可追溯。
- 统一异常与响应体、参数校验、OpenAPI 文档、极简演示页（原生 JS，4 个 Tab，5 秒自动刷新）。
- 23 个单元测试覆盖 cron 校验、任务 CRUD、执行记录状态机与触发编排，`mvn clean verify` 全绿。

> 后续（进行中再写）：时间轮 + Redisson 选主 + fencing token，实现集群下同一任务只触发一次。

---

## 8. 第 2 周开工建议

**顺序：先时间轮，后选主，最后双实例验收。** 原因是选主和触发同时改会让问题不可定位。

| 天 | 目标 | 关键点 |
|---|---|---|
| Day 1 | 时间轮 + cron 自动触发 | 轮转桶 + `next_trigger_time` 推进；先单实例跑通自动触发 |
| Day 2 | 时间轮补齐 | 任务启停联动、错过触发的补偿策略 |
| Day 3 | 领导选举 | Redisson 锁 + fencing token；本地 Redis 已就绪 |
| Day 4 | 持久化与重启补偿 | 调度状态落库、实例重启后不丢任务 |
| Day 5 | 双实例验收 | **两个 scheduler 实例，同一任务只触发一次**（本周最有价值的产出） |

第 2 周要补的前置：
- `taskpilot-scheduler` 加 `redisson` 依赖，起本地 Redis
- README 补「无 Docker 本地启动」说明
- 清理 §5.2 的死配置（顺手做，5 分钟）
