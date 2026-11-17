# 200 并发复跑指标快照（Week 4 收尾④）

> 目的：让 [perf-report §1](../perf-report.md) 的拐点观测结论可复现——用 4.10/收尾② 的指标
> 直接解释"拐点不在执行器排队"。日期：2026-11-17。
> 环境备注：复跑时 Docker 正在后台拉取 prometheus/grafana 镜像，磁盘 IO 有竞争，
> TPS 250 略低于原档 266.7；**指标形态（排队/计数对账）不受影响**。

## 压测参数与结果（同目录 summary.json）

| 项 | 值 |
|---|---|
| 并发 / 循环 / ramp-up | 200 / 20 / 10s（`perf/run-perf.sh --threads 200 --tasks 4000`） |
| 请求 / 错误 | 4000 / **0（0.00%）** |
| 时长 / TPS | 16s / 250.0 |
| P50 / P95 / P99 / max | 440 / 638 / 733 / 1045 ms |

## 负载中的池排队采样（1s 间隔，JMeter 全程）

| 指标 | 观察 |
|---|---|
| `executor_queued_tasks{name="executionDispatchPool"}`（执行器调度池排队） | **峰值 0** |
| `executor_active_threads{...}` | ≤5（core16 远未打满，demoHandler 秒回） |
| `taskpilot_pool_rejected_total{pool=triggerExecutor/shardExecutor/executionDispatchPool}` | 全程 0 |
| `taskpilot_mq_queue_depth{queue="taskpilot.execution.results.q"}`（management 轮询 gauge，收尾②） | 全程 0（消费零积压） |

## 进程内计数器对账（新启动 scheduler/executor，计数从 0 起）

| 指标 | 本轮终值 | 含义 |
|---|---|---|
| `taskpilot_triggers_total{trigger_type="manual",outcome="dispatched"}` | 4000 | JMeter 4000 次触发全部通过守卫 |
| `taskpilot_dispatch_seconds_count{outcome="accepted"}` | 4000 | 4000 次执行器 ACK 往返全部受理 |
| `taskpilot_executions_total{handler="demoHandler",status="success"}` | 4000 | 执行器侧 4000 次执行完成 |
| `taskpilot_results_published_total{outcome="confirmed"}` | 4000 | 结果消息全部拿到 broker confirm |
| `taskpilot_execution_results_total{outcome="applied",status="success"}` | 4000 | 调度侧 4000 条结果全部落库 |

## 结论

链路各环节 4000 全量对账一致、无拒绝、无积压，而触发请求 P50 已到 440ms——
**拐点的慢不在执行器排队或 MQ 消费，在触发请求自身的往返路径**
（Tomcat/Hikari 连接排队 + ACK 往返），与 perf-report §1 修正句一致。
排除法本身即指标的价值。

原始 `.jtl` 与 HTML 报告在 `.tmp/perf/`（gitignore），本目录只存小摘要。
