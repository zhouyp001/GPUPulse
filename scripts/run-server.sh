#!/usr/bin/env bash
# 启动中央服务器（纯 Netty）。先确保依赖库就绪，再从仓库根目录运行。
set -euo pipefail
cd "$(dirname "$0")/.."
"$PWD/scripts/copy-libs.sh" >/dev/null
exec java -Dfile.encoding=UTF-8 -jar server/target/monitor-server.jar "$@"
