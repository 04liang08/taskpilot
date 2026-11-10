#!/usr/bin/env bash
# Week 1 API 冒烟示例。用法：bash scripts/curl-examples.sh
set -euo pipefail

BASE="${BASE:-http://localhost:8080/api/v1}"

echo "== 1. create task"
curl -s -X POST "$BASE/tasks" \
  -H 'Content-Type: application/json' \
  -d '{
    "taskName":"demo-every-30s",
    "taskGroup":"DEFAULT",
    "cronExpression":"*/30 * * * * *",
    "handlerName":"demoHandler",
    "handlerParam":"{\"message\":\"hello\"}",
    "shardTotal":1,
    "timeoutSeconds":60,
    "retryMax":0,
    "retryIntervalSeconds":10,
    "description":"Week1 demo"
  }'
echo

echo "== 2. list tasks"
curl -s "$BASE/tasks?page=1&size=20"
echo

echo "== 3. validate cron"
curl -s -X POST "$BASE/cron/validate" \
  -H 'Content-Type: application/json' \
  -d '{"expression":"*/30 * * * * *"}'
echo

echo "== 4. trigger task 1"
curl -s -X POST "$BASE/tasks/1/trigger"
echo

echo "== 5. execution logs"
curl -s "$BASE/executions?page=1&size=20"
echo

echo "== 6. executors"
curl -s "$BASE/executors?page=1&size=20"
echo

echo "== 7. executor handlers (8081)"
curl -s http://localhost:8081/api/v1/executor/handlers
echo

echo "== 8. dashboard summary"
curl -s "$BASE/dashboard/summary"
echo