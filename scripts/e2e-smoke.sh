#!/usr/bin/env bash
# TaskPilot CI 冒烟脚本（bash / Linux）。
#
# 与 scripts/verify-week3.ps1、scripts/verify-week4.ps1 的关系：
#   - .ps1 是**本机完整验收**（Windows，5~6 个场景，几分钟，按端口定位进程）；
#   - 本脚本是 **CI 冒烟子集**（Linux，3 个场景，约 90 秒，按 PID 管理进程）。
# 两者并存、互不替代：CI 只跑"快且关键"的子集，完整验收仍然在本机跑。
#
# 前置：
#   - MySQL 可达且已执行 deploy/sql/01-init.sql 建表；
#   - Redis 可达（领导选举）；
#   - RabbitMQ 可达（结果异步上报）；
#   - 两个 jar 已由 `mvn package` 产出；
#   - PATH 里有 curl 与 mysql 客户端（查死信表用）。
#
# 依赖刻意只用 coreutils（grep/sed/date/nohup）+ curl + mysql：
# CI 的 ubuntu runner 上这些都有，本机 Git Bash 上也能跑，不依赖 jq / python。
#
# 用法：bash scripts/e2e-smoke.sh
# 退出码：0 = 全部通过，1 = 有断言失败。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/.tmp/e2e-smoke"
SCHEDULER_JAR="$ROOT/taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar"
EXECUTOR_JAR="$ROOT/taskpilot-executor/target/taskpilot-executor-1.0.0-SNAPSHOT.jar"

: "${MYSQL_HOST:=127.0.0.1}"
: "${MYSQL_PORT:=3306}"
: "${MYSQL_USER:=taskpilot}"
: "${MYSQL_PASSWORD:=taskpilot}"
: "${MYSQL_DATABASE:=taskpilot}"
: "${REDIS_HOST:=127.0.0.1}"
: "${REDIS_PORT:=6379}"
: "${RABBIT_HOST:=127.0.0.1}"
: "${RABBIT_PORT:=5672}"
: "${SCHEDULER_PORT:=8080}"
: "${EXECUTOR_PORT:=8081}"
: "${AUTH_TOKEN:=taskpilot-dev-token}"
# 任务间隔：CI 里不用等太久，够跑完一轮即可。
: "${CRON_SECONDS:=5}"

BASE="http://127.0.0.1:${SCHEDULER_PORT}/api/v1"
FAILURES=0
SCHEDULER_PID=""
EXECUTOR_PID=""
TASK_IDS=()

# ---------------------------------------------------------------- 输出
step() { echo ""; echo "=== $1 ==="; }
ok() { echo "  [OK]   $1"; }
fail() { echo "  [FAIL] $1"; FAILURES=$((FAILURES + 1)); }
assert_eq() {
    if [ "$1" = "$2" ]; then ok "$3"; else fail "$3（期望 '$1'，实际 '$2'）"; fi
}
assert_ge() {
    if [ "${1:-0}" -ge "$2" ]; then ok "$3"; else fail "$3（期望 ≥ $2，实际 ${1:-空}）"; fi
}

# ---------------------------------------------------------------- 基础工具
# 等 TCP 端口可连（用 bash 内建的 /dev/tcp，不依赖 nc / ss）。
wait_tcp() {
    local host=$1 port=$2 timeout=$3 i=0
    while [ "$i" -lt $((timeout * 2)) ]; do
        # 子 shell 里开 fd 3 连端口；失败就退化成"连不上"。
        # 注意：这里不能写 `exec 3<&- 3>&- 2>/dev/null`——exec 的重定向对当前 shell
        # 永久生效，会把后面所有 stderr（含 set -x 的 trace）一起吞掉。
        if (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; then
            exec 3<&- 3>&-
            return 0
        fi
        sleep 0.5
        i=$((i + 1))
    done
    return 1
}

# 等 HTTP 健康检查通过。
wait_http() {
    local url=$1 timeout=$2 i=0
    while [ "$i" -lt $((timeout * 2)) ]; do
        if curl -fsS -m 3 "$url" >/dev/null 2>&1; then return 0; fi
        sleep 0.5
        i=$((i + 1))
    done
    return 1
}

api() {
    local method=$1 path=$2 body=${3:-}
    if [ -n "$body" ]; then
        curl -sS -m 30 -X "$method" -H 'Content-Type: application/json' -d "$body" "${BASE}${path}"
    else
        curl -sS -m 30 -X "$method" "${BASE}${path}"
    fi
}

# 从 JSON 里取字段（字符串）。
json_str() {
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" | head -n1 |
        sed "s/.*\"$1\"[[:space:]]*:[[:space:]]*\"//; s/\"[[:space:]]*,\?[[:space:]]*$//"
}

# 从 JSON 里取字段（数字）。
json_num() {
    grep -o "\"$1\"[[:space:]]*:[[:space:]]*[-0-9]\+" | head -n1 |
        sed "s/.*\"$1\"[[:space:]]*:[[:space:]]*//"
}

mysql_query() {
    mysql --protocol=TCP -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
        -u "$MYSQL_USER" "-p${MYSQL_PASSWORD}" -D "$MYSQL_DATABASE" \
        -N -B -e "$1" 2>/dev/null
}

new_task() {
    # new_task <name> <cron> <handler> <param> <shardTotal> <timeout> <retryMax> <retryInterval>
    local body resp id
    body=$(cat <<JSON
{"taskName":"$1","taskGroup":"DEFAULT","cronExpression":"$2","handlerName":"$3","handlerParam":"$4","shardTotal":$5,"timeoutSeconds":$6,"retryMax":$7,"retryIntervalSeconds":$8,"description":"scripts/e2e-smoke.sh"}
JSON
)
    resp=$(api POST /tasks "$body")
    id=$(echo "$resp" | json_num id)
    if [ -z "$id" ]; then
        echo "  [DEBUG] 创建任务失败，响应：$resp" >&2
    fi
    if [ -n "$id" ]; then TASK_IDS+=("$id"); fi
    echo "$id"
}

start_app() {
    # start_app <name> <jar> <args...>：后台起进程并回显 PID
    local name=$1 jar=$2
    shift 2
    nohup java -jar "$jar" "$@" >"${LOG_DIR}/${name}.log" 2>&1 &
    echo $!
}

cleanup() {
    local id
    for id in ${TASK_IDS[@]+"${TASK_IDS[@]}"}; do
        api DELETE "/tasks/${id}" >/dev/null 2>&1 || true
    done
    [ -n "$SCHEDULER_PID" ] && kill "$SCHEDULER_PID" 2>/dev/null
    [ -n "$EXECUTOR_PID" ] && kill "$EXECUTOR_PID" 2>/dev/null
    sleep 1
    [ -n "$SCHEDULER_PID" ] && kill -9 "$SCHEDULER_PID" 2>/dev/null
    [ -n "$EXECUTOR_PID" ] && kill -9 "$EXECUTOR_PID" 2>/dev/null
    return 0
}
trap cleanup EXIT

# ---------------------------------------------------------------- 准备
step "准备"
command -v curl >/dev/null 2>&1 || { echo "缺少 curl"; exit 1; }
command -v mysql >/dev/null 2>&1 || { echo "缺少 mysql 客户端（查死信表要用）"; exit 1; }
for jar in "$SCHEDULER_JAR" "$EXECUTOR_JAR"; do
    [ -f "$jar" ] || { echo "找不到构建产物 $jar，请先执行 mvn package"; exit 1; }
done
rm -rf "$LOG_DIR"
mkdir -p "$LOG_DIR"

wait_tcp "$MYSQL_HOST" "$MYSQL_PORT" 10 || { echo "MySQL ${MYSQL_HOST}:${MYSQL_PORT} 不可达"; exit 1; }
wait_tcp "$REDIS_HOST" "$REDIS_PORT" 10 || { echo "Redis ${REDIS_HOST}:${REDIS_PORT} 不可达"; exit 1; }
wait_tcp "$RABBIT_HOST" "$RABBIT_PORT" 10 || { echo "RabbitMQ ${RABBIT_HOST}:${RABBIT_PORT} 不可达"; exit 1; }
echo "  MySQL / Redis / RabbitMQ 均可达"

JDBC_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

step "启动 scheduler + executor"
SCHEDULER_PID=$(start_app scheduler "$SCHEDULER_JAR" \
    "--server.port=${SCHEDULER_PORT}" \
    "--spring.datasource.url=${JDBC_URL}" \
    "--spring.datasource.username=${MYSQL_USER}" \
    "--spring.datasource.password=${MYSQL_PASSWORD}" \
    "--taskpilot.auth.token=${AUTH_TOKEN}" \
    "--taskpilot.scheduler.election.redis-address=redis://${REDIS_HOST}:${REDIS_PORT}" \
    "--spring.rabbitmq.host=${RABBIT_HOST}" \
    "--spring.rabbitmq.port=${RABBIT_PORT}" \
    "--taskpilot.executor.heartbeat-timeout-seconds=10" \
    "--taskpilot.executor.health-check-interval-seconds=3")
EXECUTOR_PID=$(start_app executor "$EXECUTOR_JAR" \
    "--server.port=${EXECUTOR_PORT}" \
    "--taskpilot.executor.address=127.0.0.1:${EXECUTOR_PORT}" \
    "--taskpilot.executor.scheduler-base-url=http://127.0.0.1:${SCHEDULER_PORT}" \
    "--taskpilot.auth.token=${AUTH_TOKEN}" \
    "--spring.rabbitmq.host=${RABBIT_HOST}" \
    "--spring.rabbitmq.port=${RABBIT_PORT}")

if ! wait_http "http://127.0.0.1:${SCHEDULER_PORT}/actuator/health" 60; then
    echo "scheduler 健康检查未通过，日志：${LOG_DIR}/scheduler.log"
    tail -n 30 "${LOG_DIR}/scheduler.log" 2>/dev/null || true
    exit 1
fi
if ! wait_http "http://127.0.0.1:${EXECUTOR_PORT}/actuator/health" 60; then
    echo "executor 健康检查未通过，日志：${LOG_DIR}/executor.log"
    tail -n 30 "${LOG_DIR}/executor.log" 2>/dev/null || true
    exit 1
fi
ok "scheduler / executor 已就绪"

# 等执行器注册上来（注册要带 token 通过鉴权，否则就是鉴权配错了）。
i=0
ONLINE=""
while [ "$i" -lt 60 ]; do
    ONLINE=$(api GET /dashboard/summary | json_num onlineExecutorCount)
    [ "${ONLINE:-0}" -ge 1 ] && break
    sleep 1
    i=$((i + 1))
done
assert_ge "${ONLINE:-0}" 1 "执行器已注册 ONLINE（内部接口鉴权通过）"

# ---------------------------------------------------------------- ①
step "① cron 任务：RUNNING -> SUCCESS 经 RabbitMQ 落库"
T1=$(new_task "ci-smoke-cron-${RANDOM}" "*/${CRON_SECONDS} * * * * *" sleepHandler '{\"millis\":3000}' 1 30 0 10)
if [ -z "$T1" ]; then
    fail "创建 cron 任务失败"
else
    ok "创建 cron 任务 id=$T1"
    i=0
    RUNNING_ID=""
    while [ "$i" -lt 60 ]; do
        RUNNING_ID=$(api GET "/executions?page=1&size=100&taskId=${T1}&status=RUNNING" | json_num id)
        [ -n "$RUNNING_ID" ] && break
        sleep 1
        i=$((i + 1))
    done
    if [ -z "$RUNNING_ID" ]; then
        fail "没等到 RUNNING 的 cron 执行记录"
    else
        ok "捕获 RUNNING 记录 id=$RUNNING_ID"
        i=0
        FINAL_STATUS=""
        while [ "$i" -lt 60 ]; do
            FINAL_STATUS=$(api GET "/executions/${RUNNING_ID}" | json_str status)
            [ -n "$FINAL_STATUS" ] && [ "$FINAL_STATUS" != "RUNNING" ] && break
            sleep 1
            i=$((i + 1))
        done
        assert_eq "SUCCESS" "$FINAL_STATUS" "结果经 RabbitMQ 回传后落成 SUCCESS"
    fi
fi

# ---------------------------------------------------------------- ②
step "② 手动触发：接口快回 RUNNING"
T2=$(new_task "ci-smoke-manual-${RANDOM}" "0 0 0 1 1 *" sleepHandler '{\"millis\":3000}' 1 30 0 10)
if [ -z "$T2" ]; then
    fail "创建手动触发任务失败"
else
    START_MS=$(date +%s%3N)
    RESP=$(api POST "/tasks/${T2}/trigger")
    COST_MS=$(( $(date +%s%3N) - START_MS ))
    STATUS=$(echo "$RESP" | json_str status)
    EXEC_ID=$(echo "$RESP" | json_num executionId)
    assert_eq "RUNNING" "$STATUS" "手动触发立即返回 RUNNING"
    if [ "$COST_MS" -ge 2000 ]; then
        fail "手动触发应该 < 2s 返回（实际 ${COST_MS} ms）"
    else
        ok "手动触发 < 2s 返回（${COST_MS} ms）"
    fi
    i=0
    FINAL_STATUS=""
    while [ "$i" -lt 60 ]; do
        FINAL_STATUS=$(api GET "/executions/${EXEC_ID}" | json_str status)
        [ -n "$FINAL_STATUS" ] && [ "$FINAL_STATUS" != "RUNNING" ] && break
        sleep 1
        i=$((i + 1))
    done
    assert_eq "SUCCESS" "$FINAL_STATUS" "轮询到终态 SUCCESS"
fi

# ---------------------------------------------------------------- ③
step "③ 重试预算耗尽 -> 死信表有行 + 告警日志"
T3=$(new_task "ci-smoke-dead-${RANDOM}" "*/${CRON_SECONDS} * * * * *" httpHandler \
    '{\"url\":\"http://127.0.0.1:9/unreachable\"}' 1 10 1 3)
if [ -z "$T3" ]; then
    fail "创建重试耗尽任务失败"
else
    ok "创建必失败任务 id=$T3"
    i=0
    DEAD=0
    while [ "$i" -lt 90 ]; do
        DEAD=$(mysql_query "SELECT COUNT(*) FROM tp_dead_letter WHERE task_id=${T3};" | tr -d '[:space:]')
        [ "${DEAD:-0}" -ge 1 ] && break
        sleep 2
        i=$((i + 1))
    done
    assert_ge "${DEAD:-0}" 1 "tp_dead_letter 里出现该任务的死信行"
    if grep -q "dead letter recorded" "${LOG_DIR}/scheduler.log" 2>/dev/null; then
        ok "调度中心日志里有死信 / 告警记录"
    else
        fail "调度中心日志里没有死信记录"
    fi
fi

# ---------------------------------------------------------------- 汇总
step "汇总"
if [ "$FAILURES" -gt 0 ]; then
    echo "冒烟未通过：${FAILURES} 项断言失败" >&2
    exit 1
fi
echo "冒烟通过"
exit 0
