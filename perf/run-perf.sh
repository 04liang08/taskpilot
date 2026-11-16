#!/usr/bin/env bash
# TaskPilot 压测脚本：JMeter headless 跑 perf/taskpilot-trigger.jmx。
#
# 关键设计（不做这一步，测出来的数字全是假的）：
#   同一任务并发触发会被 existsRunning 挡成 40900（"任务正在执行中"），
#   所以压测前先预建一批任务，压测时轮换着触发不同的 taskId。
#
# 前置：调度中心已启动（默认 http://127.0.0.1:8080），JMeter 已解压。
# 用法：
#   JMETER_HOME=/path/to/apache-jmeter-5.6.3 bash perf/run-perf.sh
#   bash perf/run-perf.sh --threads 100 --loops 20 --rampup 10
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PERF_DIR="$ROOT/.tmp/perf"
TASK_IDS_FILE="$PERF_DIR/task-ids.txt"
JMX="$ROOT/perf/taskpilot-trigger.jmx"

: "${HOST:=127.0.0.1}"
: "${PORT:=8080}"
: "${THREADS:=50}"
: "${LOOPS:=20}"
: "${RAMPUP:=10}"
# 预建任务数：必须 >= 并发线程数，否则并发会撞同一个任务（40900）。
# 实测经验：触发接口吞吐约 100+/s、执行管道滞留数秒，任务池要覆盖
# 「TPS × 滞留时间」才不会把冲突算进错误率，缺省取 线程数 × 40。
: "${TASK_COUNT:=0}"
# 预建/清理的并发度（串行 curl 建几千个任务太慢）
: "${CREATE_PARALLEL:=8}"
: "${SUSTAINED_SECONDS:=60}"
BASE="http://${HOST}:${PORT}/api/v1"

# ---------- 参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --threads) THREADS=$2; shift 2 ;;
        --loops) LOOPS=$2; shift 2 ;;
        --rampup) RAMPUP=$2; shift 2 ;;
        --tasks) TASK_COUNT=$2; shift 2 ;;
        --sustained) SUSTAINED_SECONDS=$2; shift 2 ;;
        *) echo "未知参数：$1"; exit 2 ;;
    esac
done
# 缺省任务池随并发缩放：40900 冲突率主导错误率的最初教训（见 docs/perf-report.md §3.1）
if [ "$TASK_COUNT" -eq 0 ]; then
    TASK_COUNT=$((THREADS * 40))
fi

# ---------- JMeter 可执行文件 ----------
resolve_jmeter() {
    local candidates=()
    [ -n "${JMETER_HOME:-}" ] && candidates+=("$JMETER_HOME/bin/jmeter" "$JMETER_HOME/bin/jmeter.bat")
    candidates+=("$ROOT/.tmp/jmeter/apache-jmeter-5.6.3/bin/jmeter" "$ROOT/.tmp/jmeter/apache-jmeter-5.6.3/bin/jmeter.bat")
    local c
    for c in "${candidates[@]}"; do
        [ -f "$c" ] && { echo "$c"; return 0; }
    done
    return 1
}

# ---------- 工具 ----------
json_num() {
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*[-0-9]\+" | head -n1 |
        sed "s/.*\"$1\"[[:space:]]*:[[:space:]]*//"
}

api() {
    local method=$1 path=$2 body=${3:-}
    if [ -n "$body" ]; then
        curl -sS -m 30 -X "$method" -H 'Content-Type: application/json' -d "$body" "${BASE}${path}"
    else
        curl -sS -m 30 -X "$method" "${BASE}${path}"
    fi
}

# ---------- 准备 ----------
echo "=== 准备 ==="
JMETER_CMD=$(resolve_jmeter) || { echo "找不到 JMeter，请设置 JMETER_HOME"; exit 1; }
echo "  JMeter : $JMETER_CMD"
if ! curl -fsS -m 5 "${BASE}/dashboard/summary" >/dev/null 2>&1; then
    echo "调度中心 ${BASE} 不可达，请先启动 scheduler"; exit 1
fi
echo "  调度中心 ${BASE} 可达"

rm -rf "$PERF_DIR"
mkdir -p "$PERF_DIR"
: > "$TASK_IDS_FILE"

echo "=== 预建 ${TASK_COUNT} 个任务（避免并发触发同一任务被 40900 挡掉）==="
# 串行 curl 建几千个任务太慢，用 xargs 并发；每个子进程写自己的 parts 文件避免追加竞争。
RUN_TS=$(date +%s)
parts_dir="$PERF_DIR/parts"
mkdir -p "$parts_dir"
export BASE
seq 1 "$TASK_COUNT" | xargs -P "$CREATE_PARALLEL" -I '@@' sh -c '
    body="{\"taskName\":\"perf-'"$RUN_TS"'-@@\",\"taskGroup\":\"PERF\",\"cronExpression\":\"0 0 0 1 1 *\",\"handlerName\":\"demoHandler\",\"handlerParam\":\"{}\",\"shardTotal\":1,\"timeoutSeconds\":30,\"retryMax\":0,\"retryIntervalSeconds\":10}"
    resp=$(curl -sS -m 30 -X POST -H "Content-Type: application/json" -d "$body" "${BASE}/tasks" 2>/dev/null)
    id=$(printf "%s" "$resp" | grep -o "\"id\"[[:space:]]*:[[:space:]]*[-0-9]\+" | head -n1 | sed "s/.*:[[:space:]]*//")
    [ -n "$id" ] || exit 1
    printf "%s\n" "$id" > "$1/@@.txt"
' sh "$parts_dir" || { echo "  预建任务批次失败"; exit 1; }
cat "$parts_dir"/*.txt > "$TASK_IDS_FILE" 2>/dev/null
CREATED=$(wc -l < "$TASK_IDS_FILE" | tr -d ' ')
if [ "$CREATED" -lt "$TASK_COUNT" ]; then
    echo "  只预建了 ${CREATED} 个任务（< ${TASK_COUNT}），中止"; exit 1
fi
rm -rf "$parts_dir"
echo "  已创建 ${CREATED} 个任务，ID 列表：$TASK_IDS_FILE"

# ---------- JMeter ----------
echo "=== JMeter：${THREADS} 线程 / ${RAMPUP}s ramp-up / ${LOOPS} 轮 ==="
"$JMETER_CMD" -n -t "$JMX" \
    -l "$PERF_DIR/results.jtl" \
    -e -o "$PERF_DIR/report" \
    -Jhost="$HOST" -Jport="$PORT" \
    -Jthreads="$THREADS" -Jrampup="$RAMPUP" -Jloops="$LOOPS" \
    -JtaskIdsFile="$TASK_IDS_FILE"
JMETER_EXIT=$?
if [ "$JMETER_EXIT" -ne 0 ]; then
    echo "JMeter 退出码 $JMETER_EXIT"; exit "$JMETER_EXIT"
fi

# ---------- 汇总 ----------
echo "=== 汇总 ==="
JTL="$PERF_DIR/results.jtl"
awk -F, 'NR>1 {print $2}' "$JTL" | sort -n > "$PERF_DIR/elapsed.txt"
TOTAL=$(wc -l < "$PERF_DIR/elapsed.txt" | tr -d ' ')
ERRORS=$(awk -F, 'NR>1 && $8=="false"' "$JTL" | wc -l | tr -d ' ')
FIRST=$(awk -F, 'NR==2 {print $1; exit}' "$JTL")
LAST=$(awk -F, 'END {print $1}' "$JTL")
ELAPSED_S=$(( (LAST - FIRST) / 1000 ))
[ "$ELAPSED_S" -le 0 ] && ELAPSED_S=1
TPS=$(awk -v t="$TOTAL" -v s="$ELAPSED_S" 'BEGIN { printf "%.1f", t / s }')
pct() {
    local p=$1
    [ "$TOTAL" -eq 0 ] && { echo 0; return; }
    local idx=$(( (TOTAL * p + 99) / 100 ))
    [ "$idx" -lt 1 ] && idx=1
    sed -n "${idx}p" "$PERF_DIR/elapsed.txt"
}
P50=$(pct 50); P95=$(pct 95); P99=$(pct 99)
ERR_RATE=$(awk -v e="$ERRORS" -v t="$TOTAL" 'BEGIN { printf "%.2f", t == 0 ? 0 : e * 100 / t }')

echo "  请求数        : $TOTAL"
echo "  错误数        : $ERRORS（$ERR_RATE %）"
echo "  耗时          : ${ELAPSED_S}s"
echo "  TPS           : $TPS"
echo "  P50 / P95 / P99 : ${P50} / ${P95} / ${P99} ms"
echo "  HTML 报告     : $PERF_DIR/report/index.html"

cat > "$PERF_DIR/summary.json" <<JSON
{
  "threads": $THREADS,
  "loops": $LOOPS,
  "rampUpSeconds": $RAMPUP,
  "taskCount": $TASK_COUNT,
  "requests": $TOTAL,
  "errors": $ERRORS,
  "errorRatePercent": $ERR_RATE,
  "durationSeconds": $ELAPSED_S,
  "tps": $TPS,
  "p50Ms": $P50,
  "p95Ms": $P95,
  "p99Ms": $P99
}
JSON

# ---------- 异步管道持续负载 ----------
if [ "$SUSTAINED_SECONDS" -gt 0 ]; then
    echo ""
    echo "=== 异步管道持续负载（每 1 秒触发一次，观测 ${SUSTAINED_SECONDS}s）==="
    name="perf-sustained-$(date +%s)-${RANDOM}"
    body="{\"taskName\":\"${name}\",\"taskGroup\":\"PERF\",\"cronExpression\":\"*/1 * * * * *\",\"handlerName\":\"demoHandler\",\"handlerParam\":\"{}\",\"shardTotal\":1,\"timeoutSeconds\":30,\"retryMax\":0,\"retryIntervalSeconds\":10}"
    SUSTAINED_ID=$(api POST /tasks "$body" | json_num id)
    if [ -z "$SUSTAINED_ID" ]; then
        echo "  创建持续负载任务失败"
    else
        sleep "$SUSTAINED_SECONDS"
        RECORDS=$(api GET "/executions?page=1&size=100&taskId=${SUSTAINED_ID}" | grep -o '"id"[[:space:]]*:[[:space:]]*[-0-9]\+' | wc -l | tr -d ' ')
        RUNNING=$(api GET "/executions?page=1&size=100&taskId=${SUSTAINED_ID}&status=RUNNING" | grep -o '"id"[[:space:]]*:[[:space:]]*[-0-9]\+' | wc -l | tr -d ' ')
        QUEUE_DEPTH=""
        if [ -n "${RABBIT_MANAGEMENT_URL:-}" ]; then
            QUEUE_DEPTH=$(curl -fsS -m 5 -u "${RABBIT_USER:-taskpilot}:${RABBIT_PASSWORD:-taskpilot}" \
                "${RABBIT_MANAGEMENT_URL}/api/queues/%2F/taskpilot.execution.results.q" 2>/dev/null |
                grep -o '"messages"[[:space:]]*:[[:space:]]*[0-9]\+' | head -n1 |
                sed 's/.*:[[:space:]]*//')
        fi
        echo "  持续负载任务 id=$SUSTAINED_ID"
        echo "  执行记录数    : $RECORDS（理论约 ${SUSTAINED_SECONDS} 条）"
        echo "  仍处于 RUNNING: $RUNNING（消费滞后；结果消息回来才收尾）"
        echo "  MQ 队列深度   : ${QUEUE_DEPTH:-未采集（需设置 RABBIT_MANAGEMENT_URL）}"
        cat > "$PERF_DIR/sustained.json" <<JSON
{
  "taskId": $SUSTAINED_ID,
  "seconds": $SUSTAINED_SECONDS,
  "executions": $RECORDS,
  "stillRunning": $RUNNING,
  "queueDepth": "${QUEUE_DEPTH}"
}
JSON
        api DELETE "/tasks/${SUSTAINED_ID}" >/dev/null 2>&1 || true
    fi
fi

# ---------- 清理 ----------
xargs -P "$CREATE_PARALLEL" -n 1 -I '@@' \
    curl -sS -m 30 -X DELETE -o /dev/null "${BASE}/tasks/@@" \
    < "$TASK_IDS_FILE" >/dev/null 2>&1 || true
echo ""
echo "压测完成。报告：$PERF_DIR/report/index.html"
exit 0
