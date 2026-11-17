#!/usr/bin/env bash
# 启动 Agent：采集本机 GPU/自定义指标并上报中央服务器。
# 无 nvidia-smi 时加 -Dagent.simulate=true 使用模拟数据。
set -euo pipefail
cd "$(dirname "$0")/.."
"$PWD/scripts/copy-libs.sh" >/dev/null
exec java -Dfile.encoding=UTF-8 -jar agent/target/monitor-agent.jar "$@"
