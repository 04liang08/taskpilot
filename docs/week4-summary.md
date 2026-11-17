# Week 4 阶段总结（简历 / 面试素材）

> 状态：Week 4 主线（4.1~4.10）+ 收尾补丁（B-10 修复 / 队列深度 gauge / Prometheus+Grafana / 复跑归档）全部完成并通过复审。
> 本文数字均为**单机笔记本实测、独立复核**；措辞按「可写进简历但经得起追问」校准，不夸大成生产容量。
> 配套：[架构说明](architecture.md) · [技术决策 ADR-019~022](design-decisions.md) · [压测报告](perf-report.md) · [200 并发复跑归档](perf-200-rerun/metrics-snapshot.md) · [面试稿与简历一行版](interview-prep.md)

## 1. 项目一句话

TaskPilot —— 自研分布式任务调度平台（**调度中心 + 可水平扩展执行器**两段式架构）。
Java 17 / Spring Boot 3.2 / Spring Cloud Alibaba / MyBatis-Plus / MySQL 8 / Redis(Redisson) / RabbitMQ。
第 1~3 周已具备秒级时间轮调度、cron 分片并发、选主 + fencing、失败转移、退避重试、确定性幂等；
**第 4 周把「执行结果同步回包」升级为异步可靠上报 + 死信告警，并补齐内部鉴权、JMeter 压测、GitHub Actions CI、Micrometer 可观测性闭环。**

## 2. Week 4 交付清单

| 项 | 能力 | 关键实现 |
|---|---|---|
| 4.1~4.4 | **结果异步上报** | 执行器只回 ACK 快返；结果经 RabbitMQ 回传（持久化 + publisher confirm + 事务提交后手动 ACK + DLX）；手动触发统一异步、页面轮询终态；超时判定下沉执行器，僵尸回收兜底 |
| 4.5 | **死信告警** | 重试预算耗尽落 `tp_dead_letter`（按 execution_id 幂等）+ fanout 告警，webhook 可选 |
| 4.7 | **内部鉴权** | 调度中心 ↔ 执行器双向共享静态 token，常量时间比对（`MessageDigest.isEqual`）、fail-closed；管理 API 刻意开放（仅限内网）；**B-10 加固：按解码路径匹配堵编码绕过** |
| 4.8 | **压测** | JMeter 三档并发实测 + 异步管道持续负载观测 |
| 4.9 | **CI** | GitHub Actions 双 job（单测 + bash 端到端冒烟），service container 带 mysql/redis/rabbitmq |
| 4.10 | **指标** | Micrometer + Prometheus 暴露通道；触发/派发/结果/重试/失败转移/死信/leader/池拒绝业务埋点 + 自动绑定 JVM/Hikari/线程池/HTTP |
| 收尾② | **队列深度 gauge** | scheduler 轮询 RabbitMQ management API 补 `taskpilot_mq_queue_depth`（spring-rabbit 不自动绑单队列深度），broker 不可达优雅降级 |
| 收尾③ | **可视化栈** | Prometheus + Grafana 进 compose，provisioning 自动加载数据源与 10 面板 dashboard |
| 收尾④ | **复跑归档** | 200 并发复跑 + 五链 4000 全量对账入库，坐实拐点定位 |

## 3. 实测数字（简历可引用）

- 触发接口（异步 ACK 语义）单机 **50 / 100 / 200 并发 TPS 111 / 200 / 267，错误率 0%**；P50 45 / 46 / 354ms，容量拐点在 100~200 并发。
- 异步管道持续负载**消费不积压**：结果队列深度 ≈ 1、无 RUNNING 滞留。
- 手动触发 **~209ms 快返 RUNNING**；执行器下线失败转移 **~22s 接管**；重试耗尽死信幂等落库。
- **177 单测全绿**（含鉴权 / 指标 / 并发时序 / 编码绕过断言），端到端验收五场景退出码 0。
- **用指标排除法修正了压测拐点直觉**：200 并发复跑（4000 请求 0 错误、TPS 250、P50/P95/P99 = 440/638/733ms）时，执行器派发池排队**峰值 0**（active ≤ 5）、池拒绝 0、队列深度 0，而触发→派发→执行→回传→落库**五条链各计满 4000**——瓶颈不在执行器排队或 MQ 消费，**在触发请求自身往返（Tomcat/Hikari 连接排队 + ACK 往返）**。

## 4. 关键技术取舍（面试讲「为什么」）

- **RabbitMQ 而非 Redis Stream**：要成熟消息中间件语义（DLX、publisher confirm、手动 ACK）与简历技术栈；Redis Stream 撑不起「消息中间件」这条。
- **at-least-once + 业务幂等 而非 exactly-once**：分布式下 exactly-once 代价高；用确定性幂等键（`taskId:计划时间:分片号[:执行器]`）+ `uk_execution_key` + `finishIfRunning` 的 `status=RUNNING` 守卫，把「可能执行两遍」收敛成「终态只写一次」。
- **共享静态 token 而非 Spring Security**：场景是「两个服务共享一把钥匙」，一个 `OncePerRequestFilter` 足够；Spring Security 适合多用户/角色/会话，不该为内部 token 撑大依赖树。管理 API 开放是演示体验取舍，生产交给网关/网络策略。
- **CI bash 冒烟与本机 .ps1 完整验收并存**：`Get-NetTCPConnection` 是 Windows 专属，硬改跨平台会四不像；CI 只跑快且关键的子集，完整验收留本机产数字。
- **Micrometer 拉模型 + 低基数标签铁律**：标签只用有限枚举（status/trigger_type/outcome/pool/handler），**绝不用 taskId/executionId**（会打爆 Prometheus 时间线）；自动绑定优先不重复埋（actuator 已自动绑所有 `ThreadPoolTaskExecutor` 的 `executor_*`）。
- **队列深度靠 management API 轮询补齐**：spring-rabbit 的 micrometer 只绑连接/信道/收发速率，不含单队列深度；15s 轮询 + 降级是「演示刷新够用 vs API 负载可忽略」的折中。

## 5. 面试可讲的「战争故事」（体现深度与诚实）

- **压测库崩溃**：本机 MySQL 8.1.0（Innovation 版）在 ~100/s 写入下 APPCRASH，首轮数据作废 → 切 8.0 LTS 全程 0 重启。教训：压测/演示用 LTS。
- **existsRunning 反压**：同任务并发触发被挡成 40900；压测任务池必须覆盖「TPS × 执行滞留」——400 池冲突率 18.9%，2500 池 0 错误。把设计约束量化成了对照实验。
- **指标修正直觉**：原以为拐点在执行器排队，埋点后发现派发池恒空、五链 4000 全量对账无丢失，真瓶颈在触发往返 + 连接池竞争——可观测性的价值就是把「猜」变成「排除法」。
- **RestClient 双重编码坑**：队列深度轮询的 vhost 必须原样发 `%2F`，字符串 uri 会被当 uriTemplate 再编码成 `%252F` → 真实 management API 404；JDK HttpServer 单测按解码路径匹配会掩盖这一点，改 `uri(URI)` 并经真实 broker 验证才算对。
- **两个竞态遗留（B-08/B-09）**：DB 重置丢 `tp_leader` 行 → fencing 拒掉一切触发需重启自愈；陈旧 ONLINE 执行器行 + 同地址重启竞态 → 误杀在途记录。都如实记 backlog、讲清复现条件与修法。
- **B-10 鉴权加固**：filter 用原始 URI 匹配，百分号编码前缀（`%72egister`）可绕过 → 改 `getServletPath()` 解码路径匹配 + 编码前缀单测 + 起服务 curl 复验（无 token 401、带 token 200，证明编码路径确实路由到处理器）。

## 6. 诚实边界（被追问时主动说）

- 单机演示环境（应用 / 压测机 / 中间件同机竞争 CPU 与回环带宽），数字是**保守量级**不是生产容量评估。
- `demoHandler` 秒回，测的是触发与受理链路上限，不含真实业务耗时；每档仅跑 10~15s，未做长稳（soak）与故障注入。
- 静态 token 无过期 / 无轮换 / 无审计 / 无 TLS；管理 API 与 `/actuator/prometheus` 开放，仅限内网（公网需反代认证或收暴露）。
- 演示级可观测性：无告警规则、无长期存储；Grafana admin/admin 是演示口令。
- 一个任务一条执行记录（shardTotal=1）；分片放大会线性增加记录与 MQ 消息量，另行评估。

## 7. 简历 bullet（标准版）

- 自研分布式任务调度平台，实现秒级时间轮调度、cron 分片并发、Redisson 选主 + MySQL fencing token、失败转移与指数退避重试，确定性幂等键保证重复触发终态只写一次。
- 将执行结果上报由同步 HTTP 重构为 RabbitMQ 异步可靠链路（持久化 + publisher confirm + 事务后手动 ACK + DLX 死信告警），手动触发统一异步快返 ~209ms，持续负载下结果队列无积压。
- 用 JMeter 完成 50/100/200 并发压测（TPS 111/200/267、0 错误），并以 Micrometer + Prometheus + Grafana 指标用排除法定位容量拐点（执行器池未饱和，瓶颈在触发往返 + Hikari 竞争）；补齐双向 token 鉴权与 GitHub Actions CI，177 单测全绿。

> 一行精简版与 STAR 面试稿见 [interview-prep.md](interview-prep.md)。