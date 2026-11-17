#!/usr/bin/env bash
# 后台启动服务（守护进程）。可通过环境变量 JAVA_TOOL_OPTIONS 传入 -D 配置。
# 例：JAVA_TOOL_OPTIONS="-Dagent.server.host=10.0.0.5 -Dagent.server.id=web01" ./start.sh
set -euo pipefail
cd "$(dirname "$0")"

JAR=$(ls -1 *.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  echo "错误: 未找到 jar 文件" >&2
  exit 1
fi

PID_FILE=".pid"
LOG_FILE="app.log"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "已在运行 pid=$(cat "$PID_FILE")"
  exit 0
fi

nohup java ${JAVA_OPTS:-} -Dfile.encoding=UTF-8 -jar "$JAR" >> "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "已启动 $JAR pid=$(cat "$PID_FILE")  log=$LOG_FILE"
