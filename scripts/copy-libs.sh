#!/usr/bin/env bash
# 把运行期依赖 jar 拷贝到各模块 target/lib/，使 thin jar 的 Class-Path 生效。
#
# 两种模式：
#   离线模式（默认）：本机存在 M2_ADD / M2_LOCAL 两个本地缓存目录时，从缓存手工拷贝。
#   在线模式（CI / 联网）：离线目录不存在时，用 maven-dependency 插件从中央仓库解析。
# 两种模式都会保留 common 模块产物的手工拷贝。
#
# 目录可用环境变量覆盖：M2_ADD（jackson 等）、M2_LOCAL（netty 等）。
set -euo pipefail
trap 'echo "[ERR] copy-libs.sh 第 $LINENO 行执行失败: $BASH_COMMAND" >&2' ERR
cd "$(dirname "$0")/.."

M2A="${M2_ADD:-/d/workspace/addRespos}"
M2L="${M2_LOCAL:-/d/workspace/localRepository}"
V_JACKSON="${JACKSON_VERSION:-2.17.3}"
V_NETTY="${NETTY_VERSION:-4.1.76.Final}"
COMMON_JAR="$(ls common/target/monitor-common-*.jar 2>/dev/null | head -n1)"

lib_server="server/target/lib"
lib_agent="agent/target/lib"
mkdir -p "$lib_server" "$lib_agent"

# common 模块产物始终手工拷贝
if [ -n "$COMMON_JAR" ] && [ -f "$COMMON_JAR" ]; then
  cp "$COMMON_JAR" "$lib_server/"
  cp "$COMMON_JAR" "$lib_agent/"
else
  echo "警告：未找到 $COMMON_JAR，请先执行 mvn package 构建 common 模块" >&2
fi

if [ -d "$M2A" ] && [ -d "$M2L" ]; then
  echo "==> 离线模式：从本地缓存 $M2A / $M2L 拷贝依赖"
  for a in jackson-databind jackson-annotations jackson-core; do
    j="$M2A/com/fasterxml/jackson/core/$a/$V_JACKSON/$a-$V_JACKSON.jar"
    cp "$j" "$lib_server/"
    cp "$j" "$lib_agent/"
  done
  for a in netty-buffer netty-common netty-transport netty-resolver netty-codec netty-codec-http netty-handler; do
    j="$M2L/io/netty/$a/$V_NETTY/$a-$V_NETTY.jar"
    cp "$j" "$lib_server/"
  done
else
  echo "==> 在线模式：用 maven-dependency 插件从 Maven 中央仓库解析依赖"
  mvn dependency:copy-dependencies \
      -pl server -am \
      -DincludeScope=runtime \
      -DoutputDirectory="$(pwd)/server/target/lib"
  mvn dependency:copy-dependencies \
      -pl agent -am \
      -DincludeScope=runtime \
      -DoutputDirectory="$(pwd)/agent/target/lib"
fi

echo "依赖库已就绪: $lib_server / $lib_agent"
