#!/usr/bin/env bash
# 把运行期依赖 jar 从本机 Maven 本地仓库拷贝到各模块 target/lib/，
# 使 thin jar 的 Class-Path 生效。
# 说明：本机离线，无法使用 maven-shade / maven-dependency 插件；
#       jackson 在 D:\workspace\addRespos，netty 在 D:\workspace\localRepository。
set -euo pipefail
cd "$(dirname "$0")/.."

M2A="${M2_ADD:-/d/workspace/addRespos}"
M2L="${M2_LOCAL:-/d/workspace/localRepository}"
V_JACKSON="${JACKSON_VERSION:-2.17.3}"
V_NETTY="${NETTY_VERSION:-4.1.76.Final}"
COMMON_JAR="common/target/monitor-common-0.1.0-SNAPSHOT.jar"

lib_server="server/target/lib"
lib_agent="agent/target/lib"
mkdir -p "$lib_server" "$lib_agent"

cp "$COMMON_JAR" "$lib_server/"
cp "$COMMON_JAR" "$lib_agent/"

for a in jackson-databind jackson-annotations jackson-core; do
  j="$M2A/com/fasterxml/jackson/core/$a/$V_JACKSON/$a-$V_JACKSON.jar"
  cp "$j" "$lib_server/"
  cp "$j" "$lib_agent/"
done

for a in netty-buffer netty-common netty-transport netty-resolver netty-codec netty-codec-http netty-handler; do
  j="$M2L/io/netty/$a/$V_NETTY/$a-$V_NETTY.jar"
  cp "$j" "$lib_server/"
done

echo "依赖库已就绪: $lib_server / $lib_agent"
