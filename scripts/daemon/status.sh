#!/usr/bin/env bash
# 查看服务运行状态。
cd "$(dirname "$0")"

PID_FILE=".pid"
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "运行中 pid=$(cat "$PID_FILE")"
else
  echo "未运行"
fi
