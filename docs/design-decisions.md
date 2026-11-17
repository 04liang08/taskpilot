# 技术决策记录

> 记录"为什么这么做"，面试时可据此讲取舍。新增决策按 ADR 格式追加。

## ADR-001：执行器注册表自建，而不是只依赖 Nacos

**决策**：执行器信息落 `tp_executor` 表，注册/心跳由 scheduler 自己维护；Nacos 仅作为可选配置中心。

**原因**：

- 调度需要知道每个执行器的**精确地址**，分片、失败转移、重新分配都按地址定向调用。
- 如果成员列表只存在 Nacos，调度核心逻辑会被注册中心绑架，且难以做历史状态、分组、超时判定。
- 自建表可以明确 `ONLINE/OFFLINE`、最后心跳、版本号，便于排查与演示。

**代价**：需要自己实现注册、心跳、下线检测；这部分正是项目的核心难点之一。

## ADR-002：调度主链路用 RestClient 按地址调用，而不是 OpenFeign

**决策**：scheduler → executor 使用 `RestClient` 直接请求注册表中的 `address`。

**原因**：

- OpenFeign 默认按服务名做客户端负载均衡，适合"随便挑一个实例"；调度需要"指定某个执行器执行某个分片"。
- 分片/失败转移要求调用目标可控、可重试到明确地址，按地址直连语义更清晰。
- 少一层服务发现与负载均衡，链路更短，超时和错误更容易定位。

**代价**：执行器地址必须可被调度中心访问；后续如果引入服务发现，需要自己维护地址映射。

## ADR-003：cron 使用 Spring `CronExpression`（6 段），不引入 Quartz

**决策**：任务 cron 为 `秒 分 时 日 月 周` 六段，用 Spring 自带解析器校验与计算下次触发时间。

**原因**：

- 第 2 周计划自己实现时间轮，Quartz 的调度模型会与其职责重叠。
- Spring `CronExpression` 足够覆盖校验与未来触发时间预览，零额外依赖。
- 面试时可以讲清"为什么不用 Quartz"：Quartz 集群依赖数据库锁，调度模型较重，且我们要自己实现选主与时间轮。

**代价**：与 Linux crontab 的五段格式不同，页面和文档必须明确提示 6 段。

## ADR-004：手动触发使用 UUID 作为 execution_key，定时触发才用确定性键

**决策**：

- 手动触发：`execution_key = UUID`，允许用户重复触发、每次都产生新记录。
- 定时触发（第 2/3 周）：`execution_key = taskId:triggerTime:shardIndex`，唯一索引 + Redis SETNX 防重复。

**原因**：

- 手动触发的语义就是"人主动点一次"，不应被幂等键拦截。
- 定时触发是集群重复执行的高发场景，必须用确定性键配合唯一索引兜底。
- 唯一索引只做最后一道防线，前置仍用 Redis 降低无效写压力。

## ADR-005：JDK 17 而不是 21

**决策**：统一使用 JDK 17（LTS）。

**原因**：

- Spring Boot 3.2 官方支持 17+；17 是当前企业项目最常见的 LTS。
- 与本机 `JAVA_HOME`（`D:\software\JDK17`）及 IDEA Project SDK 保持一致，避免编译/运行版本漂移。

## ADR-006：Nacos 使用 `optional:` 导入

**决策**：后续接入 Nacos 时使用 `spring.config.import: optional:nacos:...`。

**原因**：本地没有 Nacos 时应用仍能启动，保证开发环境轻量；Nacos 不作为核心链路单点。

## ADR-007：第 1 周不引入 MQ

**决策**：手动触发结果同步返回，执行记录直接落库；MQ 放到第 4 周。

**原因**：第 1 周目标是端到端跑通，过早引入 MQ 会增加联调成本；同步链路更容易演示和排查。第 3 周异步化时再评估 MQ。

## ADR-008：定时触发用自实现的时间轮，而不是每秒扫表

**决策**：自己实现秒级时间轮（`TimeWheel` + `TriggerScheduler`），每秒只扫描当前秒对应的槽位；任务定义与 `next_trigger_time` 以 MySQL 为真相源，时间轮只是内存索引。

**原因**：

- 每秒全表扫描 `tp_task` 的成本随任务数线性增长，且扫描时刻和 cron 边界对不齐，容易出现"该触发时没触发、下一轮才补上"的抖动。
- 时间轮把"什么时候该触发"变成纯内存计算，DB 访问量只与**实际触发的任务数**相关，与任务总数无关。
- `next_trigger_time` 落库后，时间轮可以随时按 DB 重建：进程重启就等于免费拿到重启补偿；顺带解决"任务定义改了要重排"的问题。
- 面试能讲清"时间轮 vs 扫表 vs 延迟队列"的取舍，而不是只会用 `@Scheduled`。

**代价**：

- 自己实现轮子要处理回补（进程卡顿后落后多秒）、错过触发（misfire）这些边界。当前策略：落后超过 `max-catch-up-seconds` 直接跳到当前秒；错过的触发**不补跑**，直接推进到下一个未来时间——避免重启后突然打出一串补跑的流量。
- 目前是**单实例**语义：两个 scheduler 实例会各推各的轮子，同一任务被触发两次。这个问题靠领导选举解决（见 ADR-009）。

## ADR-009：领导选举用 Redisson 锁 + MySQL 侧 fencing token

**决策**：每个 scheduler 实例用 Redisson `RLock` 竞争 leader 身份；抢到锁后领一个单调递增的 fencing token（Redis 计数器与 MySQL 记录取较大值），写入 `tp_leader`；每次定时触发落库前先做 fencing 校验，token 落后于库里的值就拒绝本次写入。

**原因**：

- 分布式锁只能保证"同一时刻只有一个 leader"。但 leader 遇到长 GC、网络分区、进程暂停后恢复时，**它可能还认为自己持有锁**（Redis 侧锁已过期被别人接管），于是出现脑裂：两个实例同时触发同一个任务。锁本身挡不住这种情况。
- fencing token 解决的是"过期 leader 复活后继续写"：token 在存储侧单调递增，旧 leader 带着小 token 写库时被直接拒绝。这是 Martin Kleppmann 讨论 fencing 的标准做法。
- token 落 MySQL 而不是只放 Redis，是因为**被保护的数据也在 MySQL**：只有把 token 写到和业务数据同一个存储里，才能在存储侧原子地拒绝旧写入。
- token 取 `max(Redis 计数器, DB 记录 + 1)`：Redis 重启导致计数器归零时，token 也不会倒退。

**实测（双 scheduler 实例 + Redis + MySQL）**：

| 场景 | 结果 |
|---|---|
| 两个实例同时运行 | 只有 1 个成为 leader；10 秒 cron 在 35 秒内产生 **3 条**记录（未翻倍） |
| 强杀 leader | 约 26 秒后另一个实例接管（锁租约 30 秒 + 竞选间隔 2 秒），token 从 1 变 2，重新加载时间轮继续触发 |
| 接管窗口内的触发 | **跳过不补跑**（宁可漏一次也不重复执行） |
| 人为把库里 token 改大（旧 leader 变僵尸） | 旧 leader 的触发全部被拒（`40900 fencing token 失效`），执行记录数不再增长 |

**代价**：

- 每次定时触发多一次 `UPDATE tp_leader`（fencing 校验）。这只是**触发入口的一次预检**，不是写库层的全局强制：已进入执行链路的写入不再逐条校验 token，不能理解成"保证不执行两遍"。
- leader 切换期间（租约过期到接管完成）会漏掉触发。当前选择是**漏而不是重**，因为重复执行的业务代价通常更高；需要补跑的话要在业务侧做幂等。
- 早期版本执行器只注册到一个 scheduler，scheduler 挂掉后心跳会断、被误判 OFFLINE；Week 3 已改为多地址注册（ADR-013）。

## ADR-010：任务定义变更用定期对齐 DB，而不是分布式事件总线

**决策**：任务新建/更新/启停用**进程内事件**立即生效，同时 leader 每 5 秒把 `tp_task` 与内存时间轮对账一次（新增的排进来、消失的移出去、cron 变了的重排）。

**原因**：

- 任务事件只在进程内传播。任务可能是在 **follower** 上创建的（用户在任意实例的页面上操作），leader 收不到这个事件，结果就是"任务存在但永远不触发"。
- 上 Redis pub/sub 能做到即时，但引入了订阅丢失、重连、消息顺序、重复消费一整套问题；一旦 leader 换人还要重新订阅。对一个调度器来说，**最终一致 + 5 秒延迟**比**即时但脆弱**更划算。
- 对账必须幂等：已经在轮子里且 cron 没变的任务不动，否则每 5 秒重写一次 `next_trigger_time`，任务触发时间会整体漂移。

**代价**：新建/修改任务最多延迟 5 秒在集群里生效；leader 每 5 秒一次全量查询（走 `status + trigger_type` 索引，任务量大时要考虑分页拉取）。

## ADR-011：错过触发的补偿默认跳过，可配置为"补跑一次"

**决策**：`taskpilot.scheduler.misfire-policy` 默认 `SKIP`（错过的触发不补跑，直接推进到下一个未来时间）；可选 `FIRE_ONCE`（落后在 `misfire-threshold-seconds` 内的错过触发补执行**一次**）。

**原因**：

- 进程停机 30 秒、cron 10 秒一次，就积压 3 次触发。全部补跑 = 启动瞬间打出一串流量，甚至把下游打垮；全部跳过 = 业务少了几次执行。
- 默认选"跳过"是因为**重复执行的业务代价通常高于漏一次**（多扣一次款比少扣一次更糟）。需要"至少一次"语义的场景由业务侧幂等兜底。
- 补跑只补一次：否则"补跑"本身会变成雪崩。补跑后立刻把 `next_trigger_time` 推进到未来，避免 `cron.next(过去时间)` 连续返回一串过去时间导致连环补跑。
- 补跑是**立即触发**而不是塞回过去的时间槽：过去的时间槽要等轮转回来（最多 60 秒）才会被扫到，延迟不可控。

**实测**：scheduler 停机 25 秒（跨过 2 个触发点）后重启，`FIRE_ONCE` 策略下日志只出现一次 `misfire catch-up`，并立即产生一条执行记录；随后按正常节奏继续触发。

## ADR-012：分片分配用策略接口，三种策略（平均/哈希/广播）

**决策**：定义 `ShardAllocator` 接口，按 `taskpilot.scheduler.shard-allocation` 选择策略：

| 策略 | 行为 | 适用 |
|---|---|---|
| `AVERAGE`（默认） | 分片按顺序轮流落到各执行器 | 通用计算型任务 |
| `HASH` | 按 `taskId + shardIndex` 稳定映射，同一分片永远落同一个执行器 | 需要分片状态本地化的任务 |
| `BROADCAST` | 每个分片都发给所有执行器 | 数据同步/缓存刷新类任务 |

一次触发拆成 `shardTotal` 个分片，**每个分片一条执行记录**（`shard_index` / `shard_total`），分片之间互相独立，一个分片失败不影响其他分片。

**原因**：

- 分片是调度平台的核心能力之一，三种策略的语义差别很大，用策略接口隔离比写在一个方法里加 if/else 更容易扩展（后续加"按执行器负载"策略不用改触发链路）。
- 哈希策略用 `Objects.hash(taskId, shardIndex)` 而不是 `hashCode()`：保证不同进程、不同 JVM 上算出同一个结果，重启后分片归属不变。
- 分片粒度的执行记录是后面做**失败转移**的基础：知道"哪个分片在哪个执行器上"，才能在执行器下线时精确重分配那几个分片，而不是整个任务重跑。
- 广播（`BROADCAST`）下同一个分片对应多台执行器，所以幂等键要带上执行器维度（ADR-017），否则第二台起就被唯一索引挡掉，广播实际只剩一台在跑。

**代价**：

- `shardTotal` 在任务创建后修改会让历史记录的分片数不一致（老记录仍是旧的 `shard_total`）。
- 广播的执行次数是 `分片数 × 执行器数`，任务越大放大越明显，用之前先算清楚。
- 广播的失败转移目前只投给重新分配到的第一个执行器，与广播语义不完全一致，Week 3 后续要按策略细化。

## ADR-013：执行器支持配置多个调度中心地址

**决策**：`taskpilot.executor.scheduler-base-url` 支持逗号分隔多个地址，执行器注册与心跳**逐个尝试全部地址**，任意一个成功即视为成功。

**原因**：

- 第 2 周实测发现一个真实缺口：执行器只注册一个调度中心时，那个调度中心挂掉 → 心跳断了 → 30 秒后执行器被判定离线 → 明明还能跑却不再被分配任务。这在生产里是"某个实例故障导致全局执行器不可用"。
- 注册表在**共享数据库**里，所以任意一个调度中心收到心跳就足以维持 ONLINE 状态，多地址天然是冗余保护，不需要引入服务发现。

**实测**：两个调度中心 + 一个配置了双地址的执行器，强杀其中一个调度中心，35 秒后（超过 30 秒心跳超时阈值）执行器仍为 `ONLINE`，心跳时间持续刷新。

**踩坑记录**：`scheduler-base-url` 刻意保持 `String` 类型而不是 `List<String>`——Spring Boot 的 `@ConfigurationProperties` 对 List 的绑定并不会按逗号拆分命令行参数里的值（只有 `@Value` 才会），手动 `split(",")` 才可控。

## ADR-014：分片并发执行，用独立的分片线程池

**决策**：一次触发拆出的 N 个分片**并发执行**：先把所有分片的 RUNNING 记录落库，再把各分片提交到独立的 `shardExecutor` 线程池，最后等待全部结果。

**原因**：

- 串行执行时总耗时 = 各分片耗时之和。实测：4 个分片各 sleep 1 秒，串行约 4000 ms，并发 **1290 ms**。
- 分片线程池必须和触发线程池（`triggerExecutor`）**分开**：触发线程的任务会等待分片结果，如果共用一个池，触发线程全在等待分片时会把同一个池占满，分片任务在队列里永远排不上 —— 自己把自己饿死。两个池之间是单向等待（触发线程等分片，分片不等触发），不会死锁。
- 先落库再并发执行：所有分片一开始就都有记录，即使某个分片失败，其他分片的进度也不会丢。

**代价**：

- 分片并发度受 `shard-pool` 限制（默认 8/32/1024），分片特别多时会排队。
- 手动触发仍然要等所有分片跑完才返回（HTTP 是同步的），分片多时接口耗时会变长。

## ADR-015：僵尸 RUNNING 记录回收

**决策**：定时任务把"卡在 RUNNING 超过 `任务超时时间 + 宽限`"的执行记录收尾成 `FAILED`。判据直接用 `tp_task.timeout_seconds`，不设固定的绝对阈值。

**原因**：

- 调度中心在任务执行途中崩溃/被杀，记录会永远停在 `RUNNING`，概览的"运行中"计数只增不减。
- 用**任务自己的超时时间**做基准，而不是固定几分钟：任务的超时可以从 5 秒到 24 小时不等，固定阈值要么误杀长任务、要么收不干净短任务。
- 实测：任务超时 5 秒时，10 分钟前的 RUNNING 记录被回收，刚创建的 RUNNING 记录保持不变。

**另一个触发源：执行器下线**。执行器心跳超时被标记 OFFLINE 时，会把它身上还 RUNNING 的记录收尾成 FAILED。

但实测发现这条路径**很少真正触发**：执行器被强杀时，调度中心调用执行器的那次 HTTP 请求会先收到连接错误（Connection reset），由 dispatch 的异常处理直接标成 FAILED，等执行器下线事件到达时记录已经不是 RUNNING 了。它的真正价值是兜住"调度中心自己重启后遗留的记录"——而那种情况由上面的定时回收覆盖。

**代价**：

- 回收是"事后"的：僵尸记录在下一次定时任务扫描前，概览的运行中数仍然偏大。
- 依赖 `tp_task` 里还保留着任务定义；任务被逻辑删除后（`deleted=1`），其僵尸记录不会被回收（SQL 里显式排除了 `t.deleted = 0` 的反例）。

## ADR-016：失败转移复用同一条执行记录，用原子 UPDATE 抢占

**决策**：分片失败且原执行器不可用时，把这个分片**重新分配**给其他在线执行器并重试；重试**复用原来那条执行记录**（`retry_count + 1`），不新建记录。多个实例可能同时看到同一条失败记录，用一条 UPDATE 抢占：

```sql
UPDATE tp_execution_log
   SET status='RUNNING', retry_count = retry_count + 1, start_time=NOW(3), end_time=NULL, error_message=NULL
 WHERE id = ? AND status IN ('FAILED','TIMEOUT')
```

影响行数为 1 的那一方才有资格执行重试。

**两个触发点**：

1. **分片执行失败**（连接被拒、超时）——如果原执行器已经不可用就立刻转移；原执行器还在线则**不转移**（失败多半是任务自身抛异常，换台机器也白搭）。
2. **执行器心跳超时下线**——扫描它身上所有没结束的分片，逐个转移。

**原因**：

- 复用记录而不是新建：一次分片的完整生命周期只有一条记录，能看清它失败过几次、每次的错误是什么；`execution_key` 保持不变，天然满足 ADR-004 里"定时触发用确定性幂等键"的设想——同一分片的重复触发会撞唯一索引，而不是产生重复记录。
- 抢占用一条 UPDATE 而不是"先查再改"：查-改之间有并发窗口，两个实例可能同时认为该重试，导致同一个分片被执行两遍（副作用重复）。一条带状态条件的 UPDATE 把判断和占用合并成原子操作。
- 重试前先看"原执行器还在不在"：执行器活着说明失败是任务本身的问题（处理器抛异常、参数不对），转移没有意义；执行器没了才是"该换台机器重试"的场景。

**实测**（3 个执行器、分片数 3、cron 每 10 秒）：杀掉承载 shard0 的执行器后，接连 3 次触发失败都被正确**跳过**（原执行器尚未判定下线）；约 30 秒后该执行器被标记下线，失败转移触发，同一条记录从 `FAILED` 变成 `SUCCESS`、`retry_count=1`、错误信息清空，执行器从 `127.0.0.1:8082` 换成 `127.0.0.1:8083`。

**代价**：

- 转移有延迟：执行器下线判定本身要 30 秒心跳超时，所以最坏情况下失败到重试之间约 30 秒。要更快就得把心跳超时调小，或者改成执行器主动上报下线。
- 广播策略（`BROADCAST`）下重试会只投给重新分配到的第一个执行器，与广播语义不完全一致；Week 3 后续要按策略细化。
- 只对**自动触发**（cron）做失败转移：手动触发失败就是失败，由用户决定要不要重来。

## ADR-017：定时触发用确定性幂等键，唯一索引做最后一道防线

**决策**：执行记录的 `execution_key`：

- 定时触发：`taskId:计划触发时间:分片号`（如 `3:20261115161500:0`）
- 定时触发 + **一个分片有多个目标执行器**（广播策略）：`taskId:计划触发时间:分片号:执行器Id`（如 `3:20261115161500:0:11`）
- 手动触发：UUID（手动允许重复触发）

同一个幂等键已经落过库时，INSERT 撞 `uk_execution_key`，调度中心判定为**重复触发**并跳过执行，复用已有记录。

**原因**：

- 领导选举（ADR-009）已经挡住了"两个实例同时触发"，但它是**尽力而为**：锁租约过期、网络分区、旧 leader 复活都可能让两个实例短暂地都认为自己该触发。幂等键 + 唯一索引是第二道防线——它不依赖任何分布式组件的正确性，只依赖数据库的唯一约束。它挡住的是"同一计划时间点重复落库 / 重复发起"；执行器侧没有按 `executionId` 去重，整体语义仍是**至少一次 + 业务幂等**。
- 键里用的是**计划触发时间**而不是实际落库时间。这一点很关键：实际时间每次都不一样，用它算键，重复触发就会算出不同的键，幂等直接失效。
- 与失败转移天然衔接：失败转移复用同一条记录（ADR-016），键保持不变，所以"同一分片的第 N 次重试"不会撞唯一索引。

**广播为什么要多一段执行器维度**：广播策略下同一个分片会投给所有在线执行器（ADR-012）。如果所有 job 共用 `taskId:时间:分片号`，第二台执行器 INSERT 时就会撞 `uk_execution_key`，被判成"重复触发"直接跳过——**广播实际只剩一台执行器在跑，而且不报错**。加一段执行器维度后，每个"分片 × 执行器"组合有自己的键，广播数量才是 `分片数 × 执行器数`。

**代价**：

- 手动触发仍是 UUID，无法对"同一次点击"做幂等（页面有防重入按钮，但接口层没有幂等键）。
- 任务改了 cron 之后，落在同一秒的触发会撞上旧键 —— 概率极低，但理论上存在。
- 键的段数不固定（3 段或 4 段），排查时得知道当前是哪种策略。取舍是：不引入"键版本号"这种额外维度，保持键可读、可手工反查。
- **只有单目标分片的键保持不变**：多一个执行器维度就多一个去重维度，改了会影响已有记录的去重语义。

## ADR-018：退避重试，且"业务失败"也算失败

**决策**：分片执行失败后按 `retry_interval_seconds × 2^已重试次数` 退避重试，封顶 5 分钟；重试次数受任务 `retry_max` 限制，超出即终态（死信）。重试状态落在 `next_retry_time`，内存延迟队列只是快路径，定时扫描负责兜底。

**关键修正（实测才发现）**：最初只在**调用异常**（连接被拒、超时）时触发重试，实测发现"执行器正常返回但处理器执行失败（`success=false`）"根本没进重试——而这恰恰是最典型的可重试场景（处理器抛异常、依赖抖动）。修正后两条路径都会重试。

**与失败转移的分工**：

| 失败原因 | 处理 | 目标执行器 |
|---|---|---|
| 原执行器不可用（挂了/下线） | 失败转移（立即） | 换一台 |
| 原执行器还在（处理器抛异常等） | 退避重试（延迟） | 原地重试 |

**为什么重试状态要落库**：内存里的延迟队列在调度中心重启后会丢。把"下次重试时间"写进 `tp_execution_log.next_retry_time`，定时扫描就能把到期的重试捞回来——重启不丢重试。

**实测**（任务 `retry_max=2`、退避间隔 5 秒、处理器必然失败）：

```
16:15:00  触发 → FAILED → 安排退避重试（delay=5s）
16:15:05  重试 1（retry_count=1）→ 失败 → 安排退避重试（delay=10s）
16:15:15  重试 2（retry_count=2）→ 失败 → no retry budget left → 终态
```

同一条记录从 FAILED 走到 FAILED，`retry_count` 递增到 2，`next_retry_time` 最终为 NULL（终态）。

**代价**：

- 重试预算会被失败转移消耗掉：换机重试也算一次重试。
- 退避在内存里调度，精度受 JVM 调度影响；真正的到期时间以 `next_retry_time` 为准。
- 超过 `retry_max` 的失败在第 3 周只有日志和一条 FAILED 记录；第 4 周补上了死信表与告警（ADR-019）。

## ADR-019：结果异步上报走 RabbitMQ，手动触发也统一异步

**背景**：第 3 周的执行链路是**同步 HTTP**：调度中心调执行器 → 等执行器跑完（最长 `timeout_seconds + 5s`）→ 拿结果落库。三个问题：

1. **调度线程被占住**：一个 15 秒的任务就把触发线程占 15 秒，定时触发的节奏会被拖歪；
2. **手动触发要干等**：接口调用方（页面 / curl）必须等到任务跑完，页面只能转圈；
3. **超时归属混乱**：调度中心读超时、僵尸回收、执行器自己的超时判定，三处各判一次，语义打架。

### 决策一：通道选 RabbitMQ

**决策**：用 RabbitMQ 3 做执行结果的回传通道（`taskpilot.execution.results` 交换机 + `.q` 队列 + DLX + `taskpilot.alerts` fanout）。

**为什么是 RabbitMQ，不是别的**：

| 候选 | 为什么没选 |
|---|---|
| RocketMQ | 能力更强（事务消息、延迟消息、顺序消息），但 footprint 大：Broker + NameServer 两套进程，运维和内存开销都明显更高。本项目只需要"一条结果消息 + 死信 + 一条告警"，用不上它的高级特性。 |
| Redis Stream | 轻、已经在项目里（领导选举在用）。但它是"Redis 的一个数据结构"，不是消息中间件：没有交换机 / 绑定 / DLX 这套路由模型，死信得自己用消费组实现，投影状态也得自己管。放在简历里"消息中间件"这一栏，说服力不如一个真正的 MQ。 |
| Kafka | 吞吐强，但这个场景是低频、单条结果消息，用 Kafka 是杀鸡用牛刀；而且它是日志型 MQ，单条消息的"处理完就删"语义反而别扭。 |

RabbitMQ 正好卡在中间：单进程、镜像小、有 exchange / queue / DLX / publisher confirm 这些**正好用得上**的能力。

**代价**：

- 多了一个必须起来的中间件（第 2 周是 Redis，第 4 周是 RabbitMQ）。**broker 不可达时应用仍能启动**（懒连接 + `missing-queues-fatal=false`），但结果回不来，只能靠调度中心的僵尸回收兜底。
- 发布确认只能证明 broker 收到了消息，证明不了它被路由到了队列；路由失败靠日志和僵尸回收兜底。

### 决策二：拓扑与语义

```text
执行器 --(persistent + publisher confirm)--> taskpilot.execution.results (direct)
                                                │ execution-result
                                                ▼
                                    taskpilot.execution.results.q
                                                │ 消费失败 nack(requeue=false)
                                                ▼
                                       taskpilot.execution.dlx (direct)
                                                │ execution-dead
                                                ▼
                                        taskpilot.execution.dead  --> 死信表 + 告警

调度中心 --(fanout)--> taskpilot.alerts --> taskpilot.alerts.webhook --> webhook
```

- 交换机与队列全部**持久化**：broker 重启后拓扑和未消费的消息都还在。
- 结果消息带**发布确认**：没确认就不算发出去，重发 3 次（退避），仍失败只记 error 日志。
  **不做本地磁盘队列**：调度中心的僵尸回收是最终兜底（见下）。
- 消费端**手动 ACK**：处理成功 → `basicAck`；处理失败 → `basicNack(requeue=false)` → 进死信队列。
  ACK 发生在事务提交之后，所以不会出现"ack 了但数据没落库"。
- **至少一次 + DB 幂等**：结果消息可能重复投递。落终态的 SQL 带 `status = RUNNING` 前置条件，
  重复投递只会命中 0 行、不会二次覆写终态，天然幂等。

### 决策三：手动触发也统一异步

**决策**：手动触发接口立即返回 `RUNNING` 的执行记录，页面轮询 `GET /api/v1/executions/{id}` 到终态。**不再保留同步等待路径**。

**原因**：

- 两条路径（同步 / 异步）会各自漂移，最后变成两套超时语义、两套错误处理。统一成一条更省心。
- 手动触发的"正在执行"状态本来就需要在页面上看得见——异步之后它反而更直观（记录立刻变成 `RUNNING`）。

**连带处理**：

- **防并发从内存锁改成查 DB**：异步之后 `doTrigger` 秒回，进程内的锁立刻释放，防并发形同虚设；
  而且集群里别的实例根本看不到那把锁。改成"触发前查 DB 有没有该任务的 RUNNING 记录"，
  这才是集群口径。check-then-insert 之间还有毫秒级窗口，重复落库由幂等键（ADR-017）兜住。
- **汇总状态要认 `RUNNING`**：`TriggerResult.of` 原来只检查 FAILED / TIMEOUT，
  异步之后会把"还在跑"误汇总成 SUCCESS。

### 决策四：超时归属执行器，僵尸回收兜底

**决策**：任务超时由**执行器**判定并上报（执行器本来就有 `future.get(timeout)`）；
调度中心不再靠读超时猜任务超时。调度中心的**僵尸回收**收紧成"ACK 之后结果没回来"的兜底：
超过 `task.timeout_seconds + grace` 的 `RUNNING` 记录标成 **TIMEOUT**（不是 FAILED），
并给定时触发的记录挂上 `next_retry_time`，由退避扫描 / 失败转移接手。

**为什么标 TIMEOUT 而不是 FAILED**：这种情况多半是"跑太久"或"结果消息丢了"，
不是"明确失败"。标 TIMEOUT 才和执行器上报的语义一致。

**代价**：

- 结果消息丢了要等 `timeout + grace`（默认 60s + 60s）才被发现，比同步版慢。
- 执行器被强杀时在途任务的结果永远回不来，只能靠这条兜底。
- 死信表 `tp_dead_letter` 按 `execution_id` 唯一：同一条执行记录只落一行。
  "任务被停用"和"重试预算耗尽"都会落死信——前者是"这条记录再也不会被重试了"，
  后者是"重试用完了"，都需要人看一眼。

## ADR-020：内部接口用双向共享静态 token 鉴权

**背景**：第 4 周 Day 1 之前，调度中心与执行器之间的内部接口是完全开放的：

| 方向 | 接口 | 开放的后果 |
|---|---|---|
| 执行器 → 调度中心 | `POST /api/v1/executors/register`、`POST /api/v1/executors/heartbeat` | 任何人都能伪造注册/心跳，把别人的执行器顶成 OFFLINE；下线事件会误触发失败转移 |
| 调度中心 → 执行器 | `POST /api/v1/executor/execute`、`GET /api/v1/executor/handlers` | 任何人都能让执行器跑任意处理器，也能看到有哪些处理器可调 |

**决策**：用**双向共享静态 token** 保护内部接口。两个应用读同一个配置项
`taskpilot.auth.token`（环境变量 `TASKPILOT_AUTH_TOKEN`），请求头固定为 `X-TaskPilot-Token`：

- 调度中心的 `ExecutorClient` 与执行器的 `SchedulerClientFactory` 都在构造 `RestClient` 时
  用 `.defaultHeader(...)` 带上 token —— 一次配置，所有内部调用自动带上，不用每处调用点操心；
- 调度中心只保护 `POST /api/v1/executors/register` 与 `POST /api/v1/executors/heartbeat`
  （按**路径 + 方法**匹配：`GET /api/v1/executors` 是管理查询，保持开放）；
- 执行器保护 `/api/v1/executor/**`（执行 + 处理器列表），`/actuator/health` 放行。

### 为什么管理 API 保持开放

管理接口（任务 CRUD、执行记录查询、仪表盘、演示页）**不保护**，只跑在内网 demo 环境：

- 演示体验是"打开页面就能看"。全部加上鉴权就要先登录，收益（防内网里的好奇同事）
  远小于代价（演示脚本、curl 示例、验收脚本全要带 token，README 里一半的命令都要改）；
- 管理接口的破坏面比内部接口小得多：最坏是有人改了任务配置，而不是把执行器顶下线；
- 真正的生产部署里，这一层应该交给网关 / 网络策略，而不是在应用里做半套认证。

**边界写清楚**：管理 API 开放 = **仅限内网**。这一点在 README 与演示页上都要写明。

### 为什么不上 Spring Security

一个"共享静态 token 校验"用不着 Spring Security 的那一整套：过滤器链、用户体系、
密码编码器、AuthenticationProvider、@PreAuthorize 表达式……加进来的成本远大于收益，
而且会把"演示项目"的依赖树撑大。Spring Security 适合"多用户 + 角色 + 会话"的场景，
这里的场景是"两个服务之间共享一把钥匙"，一个 `OncePerRequestFilter` 就够了。

如果将来真要上 Spring Security，应该上的是"管理 API 的用户登录 + 角色"，
而不是替换掉这把内部 token —— 两者解决的是不同的问题。

### 为什么用常量时间比对

token 比对用 `MessageDigest.isEqual` 而不是 `String.equals`：`String.equals` 逐字符比较、
遇到第一个不匹配就返回，**耗时差可以被用来一位一位地猜 token**（时序侧信道）。
`MessageDigest.isEqual` 的耗时与是否匹配基本无关。

同理，**没配 token 时一律拒绝**（fail-closed）：漏配 `TASKPILOT_AUTH_TOKEN` 就把内部接口
裸奔出去，比"直接起不来"危险得多。启动时打一条 ERROR 日志说明原因。

### 按解码路径匹配（B-10 修复）

filter 判断"这个请求是否命中受保护路径"用的是 `getServletPath()`（容器已 URL 解码、
且不含 contextPath），**不是** `getRequestURI()`（原始编码串）。否则
`/api/v1/%65xecutor/execute` 这类百分号编码前缀会让 `startsWith/equals` 漏判放行，
而 Spring 的 PathPattern 按解码后路径照样把请求映射进受保护处理器——等于绕开 token。
两侧各有一条「编码前缀仍被拒」的单测钉住这个语义。日志里保留原始 URI 便于取证。

**代价**：

- 静态 token 没有过期、没有轮换、没有审计。真要上生产得换成带 TTL 的短期 token 或 mTLS。
- 两个应用必须配成同一个值：配错的话症状是"执行器注册 401"，从日志里能一眼看出来
  （`request rejected, invalid token`）。
- token 走明文 HTTP（演示环境没有 TLS）。生产环境必须上 TLS，否则 token 等于明文。
- 管理 API 仍然没有鉴权，只靠"仅限内网"这条约定兜着。

## ADR-021：CI 用 bash 冒烟子集，与本机 .ps1 完整验收并存

**决策**：GitHub Actions 两个 job：

| job | 内容 | 依赖外部服务 |
|---|---|---|
| `build-and-test` | `mvn -B clean verify`（纯单元测试）+ 上传 surefire 报告 | 无 |
| `e2e-smoke` | `bash scripts/e2e-smoke.sh`（3 个场景，约 90 秒） | MySQL / Redis / RabbitMQ（GitHub service containers） |

### 为什么 bash 冒烟脚本独立于 .ps1 完整验收

现有的 `scripts/verify-week3.ps1`、`scripts/verify-week4.ps1` 是**本机完整验收**，
覆盖 5~6 个场景、要几分钟、而且用 `Get-NetTCPConnection` 按端口定位进程 —— 这是
Windows 专属能力，ubuntu runner 上跑不了。硬把它改成跨平台，会让一个"给人看的、
带交互输出和实测数字汇总的验收脚本"变得四不像。

所以拆成两个：

- **`scripts/e2e-smoke.sh`**：Linux/bash，只保留"快且关键"的子集
  （① cron 任务经 MQ 落 SUCCESS；② 手动触发快回 RUNNING；③ 重试耗尽落死信），
  按 PID 管理进程，退出码 0/1 可接 CI；
- **`.ps1` 完整验收**：本机跑，负责产出 README / 报告里的实测数字（含 failover 计时、
  死信行数、广播记录数等），CI 不重复这些慢场景。

两者**并存、互不替代**：CI 保证"提交上去不会把主链路跑坏"，完整验收保证"发布前
五个场景都验过并留下数字"。

### bash 脚本的依赖取舍

只依赖 coreutils（grep / sed / date / nohup）+ curl + mysql 客户端：

- 不依赖 jq / python：ubuntu runner 上虽然都有，但少一个少一份风险；
- JSON 解析用 `grep -o` + `sed` 取字段，够用且没有依赖；
- 端口探测用 bash 内建的 `/dev/tcp`，不依赖 nc / ss。

踩过的坑（已修）：`exec 3<&- 3>&- 2>/dev/null` 这行里的 `2>/dev/null` 会对当前 shell
**永久**重定向 stderr，把后面所有日志（包括 `set -x` 的 trace）一起吞掉——
排查时看起来像"脚本没执行到那一步"。

### service containers 的两个注意点

1. **不会自动执行初始化脚本**。`deploy/docker-compose.yml` 靠
   `/docker-entrypoint-initdb.d` 自动跑 `deploy/sql/01-init.sql`，但 GitHub 的
   service container 没有这个机制，**建表必须手动做**：
   ```bash
   mysql -h 127.0.0.1 -u root -proot -e "GRANT ALL PRIVILEGES ON taskpilot.* TO 'taskpilot'@'%'; FLUSH PRIVILEGES;"
   mysql -h 127.0.0.1 -u taskpilot -ptaskpilot taskpilot < deploy/sql/01-init.sql
   ```
   第一条是显式保险，不是硬前提：`01-init.sql` 开头有 `CREATE DATABASE IF NOT EXISTS taskpilot`，
   而 service container 里的 `taskpilot` 用户拿的是 `ALL ON taskpilot.*`——库级 ALL 本身含 CREATE，
   恰好能建本库。GRANT 只是把这个隐含依赖写明白，省掉一般也能过。
2. **service container 映射到 runner 的 127.0.0.1**，不是容器名。所以脚本里
   MySQL / Redis / RabbitMQ 的地址都默认 `127.0.0.1`，靠环境变量覆盖。

### 打包命令

e2e job 用 `mvn -B clean package -DskipTests`（不用 `-Dmaven.test.skip=true`）：
测试代码仍然编译，测试编译失败会让 job 直接失败——这比"悄悄用旧 jar 跑冒烟"要好
（README 5.2 踩过这个坑）。测试本身由 `build-and-test` job 负责。

**代价**：

- CI 只跑 3 个场景，failover、广播、改 cron 这些慢场景不在 CI 里。
  它们靠本机完整验收 + 评审保证。
- service container 的端口是固定的（3306 / 6379 / 5672），CI runner 上如果被占用会起不来
  （GitHub 的 runner 是全新的，一般不会）。

---

## ADR-022：指标用 Micrometer + Prometheus 拉模型，定位演示级可观测性

- **日期**：2026-11-17
- **状态**：已采纳（4.10）
- **关联**：ADR-019（异步链路）、ADR-020（管理端点同样保持开放）、ADR-021、[压测报告](perf-report.md)

### 背景与问题

压测（4.8）给出了现象但给不出解释：200 并发时 P50 从 46ms 跳到 354ms、吞吐只涨 33%——
拐点到底是队列排队、连接池还是 MQ？需要把"触发 → 派发 → 执行 → 回传"链路上的关键行为
变成可持续拉取的指标，让拐点的故事能用数据讲清。

### 方案比较与决策

1. **Micrometer + actuator 的 `/actuator/prometheus`（采纳）**：依赖已在 classpath，
   加一个 `micrometer-registry-prometheus` 就得到标准格式；拉模型无需推端配置，演示环境够用。
2. 自建统计日志/自造 JSON 端点：格式非标准，接不进 Prometheus 生态，否。
3. OTel / push 网关 / 告警体系：生产监控体系，不是本项目范围，否。

### 决策要点

- **自动绑定优先，不重复埋**：actuator 已自动绑定 JVM、HikariCP、Tomcat、
  `http_server_requests_*`、rabbitmq（连接/信道/消费/发布速率）与**所有
  `ThreadPoolTaskExecutor` bean 的 `executor_*` gauge**（name 标签即 bean 名）。
  线程池排队/活跃数因此是免费的；再手写 `ExecutorServiceMetrics.monitor` 只会造出
  第二条相同时间线。自定义只补拒绝计数：`CountingRejectedExecutionHandler`
  （common，装饰 AbortPolicy：先给 `taskpilot.pool.rejected{pool=...}` +1 再原样抛，
  拒绝语义零改变）。
- **命名与标签铁律**：`taskpilot.*` 点号命名（Prometheus 渲染下划线、counter 带 `_total`），
  标签只用低基数枚举（status/trigger_type/outcome/action/pool/handler）。
  **绝不用 taskId/executionId/executorAddress 当标签**——执行记录 id 是无界增长的高基数，
  会直接打爆 Prometheus 时间线。名字常量收敛在 `common/metrics/MetricNames`。
- **执行耗时的两个口径**（同名不同渲染，互不覆盖）：
  调度中心侧 `taskpilot.execution.duration` 是 DistributionSummary（baseUnit=ms，
  值取执行器上报的 `durationMs`，即"业务耗时"）；执行器侧是 Timer（处理器环绕计时，
  含提交排队）。Prometheus 侧分别渲染 `..._ms_*`（baseUnit 缩写）与 `..._seconds_*`。
- **直方图**：`http.server.requests`（yml 配置）与 `taskpilot.execution.duration`、
  `taskpilot.dispatch`（代码 `publishPercentileHistogram()`）都开百分位直方图，
  Prometheus 可算 P95/P99，与压测报告口径对齐。
  教训：Timer/Summary **不开就没有 `_bucket` 序列**，`histogram_quantile` 面板直接空白
  ——Grafana 第 3 面板（派发 ACK P95）就是这么暴露的（收尾复审实证，回归测试钉住导出面）。
- **leader 观测零侵入**：`LeaderMetrics` 独立组件——gauge 读
  `LeaderElectionService.isLeader()`，切换计数订阅既有 `LeaderChangedEvent`，
  选举代码与既有单测都不动。
- **埋点清单**（outcome 全部为有限枚举）：
  | 指标 | 类型 | 位置 |
  |---|---|---|
  | `taskpilot.triggers{trigger_type,outcome}` | Counter | TriggerService 三个守卫分支 + 成功派发 |
  | `taskpilot.dispatch{outcome}` | Timer | TriggerService.dispatch 的 ACK 往返 |
  | `taskpilot.execution.results{status,outcome}` | Counter | ResultProcessor 两条入口（applied/ignored/dispatch_failure） |
  | `taskpilot.execution.duration{status/handler}` | Summary / Timer | ResultProcessor applied / ExecutionDispatcher run |
  | `taskpilot.retries{action}` | Counter | RetryService scheduled·handed_over + ExecutionService exhausted |
  | `taskpilot.failovers{outcome}` | Counter | ShardFailoverService 四类分支 |
  | `taskpilot.deadletters{outcome}` | Counter | DeadLetterService recorded/duplicate |
  | `taskpilot.leader` + `taskpilot.leader.transitions{to}` | Gauge/Counter | LeaderMetrics |
  | `taskpilot.pool.rejected{pool}` | Counter | CountingRejectedExecutionHandler（3 个 Abort 池） |
  | `taskpilot.executions{status,handler}` | Counter | ExecutionDispatcher run |
  | `taskpilot.results.published{outcome}` | Counter | RabbitResultChannel confirmed/gave_up |
  | `taskpilot.mq.queue.depth{queue}` | Gauge | QueueDepthMetrics 轮询 management API（收尾②） |

### 代价与边界（如实）

- **单队列深度靠轮询补齐**：spring-rabbit 的 micrometer 绑定只到连接/信道/消费与发布速率，
  **不含队列 messages 深度**。Week 4 收尾已做：scheduler 侧 `QueueDepthMetrics` 用 RabbitMQ
  management API 每 15s 轮询 `taskpilot.execution.results.q` / `taskpilot.execution.dead`
  注册 `taskpilot_mq_queue_depth{queue=...}`（broker 不可达保留上次值 + warn，不拖垮调度线程）。
  15s 是"演示刷新够用 vs management API 负载可忽略"的折中，不是精确监控口径。
  踩坑记录：management 路径里的 vhost 必须原样发送 `%2F`——RestClient 的字符串 uri 形式会被
  当 uriTemplate **再编码**成 `%252F`，真实 API 404，必须走 `uri(URI)`；JDK HttpServer 的
  context 按解码路径匹配，单测会掩盖这一点（真实集成验证过才敢说对）。
- 演示级：无告警规则、无长期存储；Prometheus + Grafana 容器已进 compose（Week 4 收尾，
  `docker compose -f deploy/docker-compose.yml up -d prometheus grafana`），
  抓取走 host.docker.internal（应用跑宿主机；Linux 靠 extra_hosts host-gateway 等价），
  Grafana admin/admin 是演示口令。命令行验证仍可用 `curl /actuator/prometheus | grep taskpilot_`。
- `/actuator/prometheus` 与其余管理端点一样**不设鉴权**（ADR-020 口径：仅限内网）。
  公网部署要么加反代认证要么关掉该端点暴露。
- 无告警规则、无长期存储（指标拉了就看，不做 alerting/recording rules）；
  可视化栈见上条与 README「可观测性栈」。
- 池 gauge 的 name 标签来自 bean 名（`triggerExecutor`/`shardExecutor`/
  `taskExecutorPool`/`executionDispatchPool`），改 bean 名会让历史时间线断档——已知即可。
