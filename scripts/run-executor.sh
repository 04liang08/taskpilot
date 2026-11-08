#!/usr/bin/env bash
# 构建并启动执行器（8081）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn -q -DskipTests -pl taskpilot-executor -am package

exec java -jar "$ROOT_DIR/taskpilot-executor/target/taskpilot-executor-1.0.0-SNAPSHOT.jar" "$@"