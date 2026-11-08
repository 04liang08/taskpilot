#!/usr/bin/env bash
# 启动本地基础设施：MySQL / Redis / Nacos
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/deploy/docker-compose.yml"

echo "==> starting mysql / redis / nacos"
docker compose -f "$COMPOSE_FILE" up -d mysql redis nacos

echo "==> waiting for mysql to become healthy (max 120s)"
for i in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' taskpilot-mysql 2>/dev/null || echo unknown)"
  if [ "$status" = "healthy" ]; then
    echo "==> mysql is healthy"
    break
  fi
  if [ "$i" -eq 60 ]; then
    echo "!! mysql did not become healthy in 120s, check: docker logs taskpilot-mysql" >&2
    exit 1
  fi
  sleep 2
done

echo
echo "MySQL     : localhost:3306  (taskpilot / taskpilot)"
echo "Redis     : localhost:6379"
echo "Nacos     : http://localhost:8848/nacos  (standalone, auth disabled)"