# TaskPilot 压测报告（4.8）

> 日期：2026-11-16。工具：Apache JMeter 5.6.3（headless）。
> 测试计划：`perf/taskpilot-trigger.jmx`；驱动脚本：`perf/run-perf.sh`。
> 原始产物（`.jtl` / HTML 报告 / `summary.json`）在 `.tmp/perf-*`（gitignore，不入库）。

## 1. 结论速览

单机默认配置下，触发接口（`POST /api/v1/tasks/{id}/trigger`，异步 ACK 语义）的实测：

| 并发线程 | 请求数 | 错误率 | 时长 | TPS | P50 | P95 | P99 | Avg | Max |
|---|---|---|---|---|---|---|---|---|---|
| 50  | 1000 | 0% | 9s  | **111.1** | 45ms  | 60ms  | 75ms  | 45.3ms | 90ms  |
| 100 | 2000 | 0% | 10s | **200.0** | 46ms  | 91ms  | 122ms | 54.4ms | 168ms |
| 200 | 4000 | 0% | 15s | **266.7** | 354ms | 541ms | 616ms | 356.1ms | 876ms |

- **吞吐与延迟的拐点在 100~200 线程之间**：50→100 并发吞吐近乎线性（111→200 TPS）且尾延迟温和；
  100→200 并发吞吐只 +33%（266.7 TPS），P50 却从 46ms 跳到 354ms——服务端开始排队，
  单机默认配置（Hikari 10 连接 / trigger-pool 4~16 / 无调优 MySQL）的容量上限约 **200~270 req/s**。
- 触发接口本身快回（ACK/RUNNING 语义），异步结果管道在持续负载下**消费不滞后**（§4）。
- 拐点的解释已可用指标观测（4.10，ADR-022）：高并发档看
  `executor_queued_tasks{name="executionDispatchPool"}` 排队抬升与
  `http_server_requests_seconds` / `taskpilot.dispatch` 直方图的 P95 上移即可对上号。

## 2. 测试环境（如实记录）

| 组件 | 配置 |
|---|---|
| 硬件/系统 | AMD Ryzen 7 7840HS（8C16T）/ 16GB RAM / Windows 11（build 26200），**全部组件同一台笔记本** |
| JDK | 17.0.16 LTS（HotSpot） |
| 应用 | `taskpilot-scheduler` 8080、`taskpilot-executor` 8081（fat jar，配置全默认：Hikari max 10、trigger-pool 4/16/queue 512、executor dispatch-pool 16/32/queue 64、MQ prefetch 10） |
| MySQL | **8.0.46**，Docker Desktop 容器，宿主端口 3307，schema 由 `deploy/sql/01-init.sql` 初始化 |
| Redis | 5.0.14.1，本机进程（leader 选举） |
| RabbitMQ | 3.13.7，Docker Desktop，5672/15672，vhost `/` |
| 压测机 | JMeter 5.6.3 headless，与被测应用同机 |

Docker Desktop 的 NAT/WSL2 转发给 MySQL 与 RabbitMQ 增加了一跳本机回环外的转发开销，
生产部署（独立主机/容器网络直连）数字会更好；本报告的数字是**保守值**。

## 3. 方法与关键设计

1. **预建任务池轮换**：同一任务并发触发会被 `existsRunning` 挡成 40900「任务正在执行中」。
   压测前批量创建 `cron=0 0 0 1 1 *`（永不自动触发）的一次性任务，JMeter 用全局自增计数器
   `idx % 任务数` 轮换 `taskId`。**任务池必须覆盖「TPS × 执行滞留时间」**，否则 40900 冲突会主导错误率。
   - 对照实验（50 线程）：任务池 400 个 → 复用间隔 ≈ 3.6s < 管道滞留 → **错误率 18.9%（全部 40900）**；
     任务池 2500 个 → **0 错误**。两档吞吐几乎相同（≈111 TPS，409 返回很快），说明冲突不压吞吐但污染错误率。
   - 因此正式三档任务池取 ≈ 线程数 × 25~40（A/B 实测均 0 错误）。
2. 断言：响应 JSON `code=0` 记成功；HTTP 非 2xx / 业务码非 0 均记错误。
3. 指标：`.jtl`（CSV）→ awk 计算 TPS 与 P50/P95/P99；HTML dashboard 报告在 `.tmp/perf-*/report/index.html`。
4. 创建/清理任务走管理 API（开放接口，无 token），压测流量本身也是管理 API 触发（ADR-020 范围外，符合设计）。
5. 三档场景串行执行，档间无重叠负载；每档跑完即归档产物（`run-perf.sh` 每次清空 `.tmp/perf`）。

复跑命令（scheduler/executor/基础设施已就绪时）：

```bash
JMETER_HOME=/path/to/apache-jmeter-5.6.3 bash perf/run-perf.sh --threads 50 --loops 20 --rampup 10 --tasks 2500 --sustained 0
```

## 4. 异步管道持续负载（200 线程场景后紧接观测）

创建 `*/1 * * * * *` cron 任务后静置 60s（数据 `.tmp/perf-200/sustained.json`）：

| 指标 | 实测 | 解读 |
|---|---|---|
| 执行记录数 | 20 / 60 秒 | 1s 一跳 cron 大部分被 `existsRunning` 反压挡掉：单次执行端到端滞留 ≈ 3s（前有大负载待消化） |
| 仍处于 RUNNING | 0 | 收尾全部落库 |
| 结果队列深度（`taskpilot.execution.results.q`） | 1 | **消费跟得上，无积压**；瓶颈不是 MQ，而是 dispatch→执行→回传→落库的往返时延 |

即：手动触发/定时触发共用异步管道（ADR-019），在 200 并发突发后 60s 内队列即可清空到个位数。

## 5. 诚实边界

- **单机演示环境，不是生产容量评估**：应用、压测机、MySQL、RabbitMQ 同机竞争 CPU 与回环带宽；
  Redis/Docker Desktop 各自还有本地开销。数字用于回答「接口是否快回、链路是否不积压、量级有多大」。
- `demoHandler` 传参 `{}` **秒回**，测的是触发与受理链路上限，不含真实业务执行时间；
  业务耗时一分，管道 dwell 就加一分，任务池需要按 §3.1 重估。
- 每档持续仅 10~15 秒，JIT/连接池预热还不完全充分，高并发档数字偏保守；未做长稳（soak）与故障注入。
- 一个任务一条执行记录（`shardTotal=1`）；分片放大会线性增加执行记录与 MQ 消息量，另行评估。
- 压测流量不带 token（管理 API 设计上开放）；内部接口的 4.7 鉴权路径不在本压测覆盖内。

## 6. 过程事故与发现（如实记录，均非被测应用代码缺陷）

1. **MySQL 8.1.0（Innovation release，Windows 原生 mysqld）压测中崩溃**：首轮 50 并发在 ~100/s 写入下
   `mysqld.exe` 发生 APPCRASH（Windows Error Reporting，0x80000003），该轮数据作废；
   改用 Docker `mysql:8.0.46` 后三档 + 持续负载全部通过且容器 0 重启。8.1 崩溃根因未深查（不在范围），
   **建议压测/演示统一用 8.0 LTS**。
2. **DB 不可用时的错误形态**：mysqld 挂掉后触发请求等待 Hikari `connectionTimeout=3000ms` 满，
   由全局兜底返回 **HTTP 500 / code=50000**（非 503/50001）。语义上「数据库连不上」更接近服务不可用，
   错误映射可在后续优化（不改行为，仅记账于此）。
3. **新遗留项 B-08（已记入 backlog）**：压测中重置 MySQL 后 `tp_leader` 行丢失，进程内 leader 身份仍在，
   但触发被 fencing 校验全部拒绝（40900），需重启 scheduler 才自愈——选举状态对 DB 重置没有自愈路径。
4. **压测后回归验收（verify-week4）首跑场景①失败**，取证为陈旧 ONLINE 执行器行的离线判定与同地址重启竞态
   误杀在途记录（**已记入 backlog B-09**）；清空 `tp_executor` 后重跑五场景全部通过、退出码 0，非代码回归。

## 7. 产出物索引

| 产物 | 位置 |
|---|---|
| JMeter 测试计划 | `perf/taskpilot-trigger.jmx` |
| 压测驱动脚本 | `perf/run-perf.sh` |
| 50 线程正式档 | `.tmp/perf-50/`（summary.json / results.jtl / report/） |
| 50 线程对照档（400 任务池） | `.tmp/perf-50-collide400/` |
| 100 线程档 | `.tmp/perf-100/` |
| 200 线程档 + 持续负载 | `.tmp/perf-200/`（含 sustained.json） |
