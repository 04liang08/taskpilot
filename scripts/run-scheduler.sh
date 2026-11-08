#!/usr/bin/env bash
# 构建并启动调度中心（8080）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -q -DskipTests -pl taskpilot-scheduler -am package

exec java -jar "$ROOT_DIR/taskpilot-scheduler/target/taskpilot-scheduler-1.0.0-SNAPSHOT.jar" "$@"