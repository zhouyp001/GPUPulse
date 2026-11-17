#!/usr/bin/env bash
# 打包：生成自包含的 dist/agent 与 dist/server 两个发布包。
# 用法：scripts/package.sh
set -Eeuo pipefail
trap 'echo "[ERR] 第 $LINENO 行执行失败: $BASH_COMMAND" >&2' ERR
cd "$(dirname "$0")/.."

echo "==> 构建模块 (mvn package)"
mvn -DskipTests package

echo "==> 拷贝依赖到各模块 target/lib"
bash scripts/copy-libs.sh

DIST="dist"
rm -rf "$DIST"
mkdir -p "$DIST/agent" "$DIST/server"

echo "==> 组装 agent 包（部署到每台被监控的 GPU 服务器）"
cp agent/target/monitor-agent.jar "$DIST/agent/"
cp -r agent/target/lib "$DIST/agent/lib"
cp scripts/daemon/start.sh scripts/daemon/stop.sh scripts/daemon/status.sh "$DIST/agent/"

echo "==> 组装 server 包（部署到监控中心机器）"
cp server/target/monitor-server.jar "$DIST/server/"
cp -r server/target/lib "$DIST/server/lib"
cp -r web "$DIST/server/web"
cp scripts/daemon/start.sh scripts/daemon/stop.sh scripts/daemon/status.sh "$DIST/server/"

cp scripts/DEPLOY.md "$DIST/README.md"

echo
echo "完成："
echo "  dist/agent    -> scp 到每台 GPU 机，运行 ./start.sh"
echo "  dist/server   -> scp 到监控中心机，运行 ./start.sh"
echo "  部署说明见 dist/README.md"
