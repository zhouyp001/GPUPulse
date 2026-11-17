#!/usr/bin/env bash
# 停止服务。
set -euo pipefail
cd "$(dirname "$0")"

PID_FILE=".pid"
if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "已停止 pid=$PID"
  else
    echo "进程 $PID 已不存在"
  fi
  rm -f "$PID_FILE"
else
  echo "未找到 pid 文件（可能未启动）"
fi
