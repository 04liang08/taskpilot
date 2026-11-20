#!/usr/bin/env bash
# TaskPilot 推送到 GitHub
# 用法：bash scripts/push-to-github.sh <你的GitHub用户名>
#
# 前提：
#   1. 已在 https://github.com/new 创建空仓库，名字填 TaskPilot
#      注意：不要勾选 Add README / .gitignore / license（本地已有）
#   2. 建议设为 Public（简历要放链接，Private 面试官看不到）
#
# 执行时会弹浏览器让你登录授权（Git Credential Manager 负责，不需要手动配 token）

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "用法：bash scripts/push-to-github.sh <你的GitHub用户名>"
  echo "例如：bash scripts/push-to-github.sh zhangshang123"
  exit 1
fi

GH_USER="$1"
REPO="TaskPilot"
REMOTE_URL="https://github.com/${GH_USER}/${REPO}.git"

cd "$(dirname "$0")/.."

echo "==> 检查本地状态"
if [ -n "$(git status --porcelain)" ]; then
  echo "!! 工作区不干净，先提交或 stash："
  git status --short
  exit 1
fi
echo "    工作区干净，共 $(git rev-list --count HEAD) 个 commit"

echo "==> 检查远端仓库是否存在"
if curl -s -o /dev/null -w "%{http_code}" -I "https://github.com/${GH_USER}/${REPO}" | grep -q "^200$"; then
  echo "    仓库已存在：https://github.com/${GH_USER}/${REPO}"
else
  echo "!! 仓库不存在。请先打开 https://github.com/new 创建 ${REPO}"
  echo "   （不要勾选 Add a README file / Add .gitignore / Choose a license）"
  exit 1
fi

echo "==> 配置 remote"
if git remote get-url origin >/dev/null 2>&1; then
  git remote set-url origin "$REMOTE_URL"
  echo "    已更新 origin -> $REMOTE_URL"
else
  git remote add origin "$REMOTE_URL"
  echo "    已添加 origin -> $REMOTE_URL"
fi

echo "==> 推送 main（会弹出浏览器登录授权）"
git push -u origin main

echo ""
echo "==> 推送完成"
echo "    仓库地址：https://github.com/${GH_USER}/${REPO}"
echo ""
echo "接下来还要做两件事："
echo "  1. 把 README.md 第 6~7 行的 USERNAME 换成 ${GH_USER}，再 commit + push："
echo "     sed -i \"s|USERNAME|${GH_USER}|g\" README.md"
echo "     git commit -am 'docs: 点亮 CI 徽章' && git push"
echo "  2. 等 Actions 跑完（约 3-5 分钟），确认徽章变绿再写进简历"