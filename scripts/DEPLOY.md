# 部署说明

本目录是打包产物。共两个自包含包，各自含 jar + lib/ 依赖 + 启动脚本，无需额外安装，兼容 JDK 8+（编译目标 release 8）。

## server 包（部署到监控中心机器）

```bash
# 1) 上传 dist/server 到监控机
scp -r dist/server root@<监控机IP>:/opt/monitor-server/

# 2) 启动（默认端口 8080）
cd /opt/monitor-server
./start.sh          # 后台运行，PID 记入 .pid，日志写入 app.log

# 3) 查看
./status.sh         # 运行状态
tail -f app.log     # 日志
./stop.sh           # 停止
```

- 面板地址：`http://<监控机IP>:8080/`
- 改端口：`JAVA_TOOL_OPTIONS="-Dserver.port=9090" ./start.sh`
  > ⚠️ 改端口后，**每台 Agent 的 `agent.server.port` 也要同步改成同一个值**，否则 Agent 仍上报旧端口、面板也访问不到。详见下方 agent 包一节。

## agent 包（部署到每台被监控的 GPU 服务器）

```bash
# 1) 上传 dist/agent 到每台 GPU 机
scp -r dist/agent root@<GPU机IP>:/opt/monitor-agent/

# 2) 启动，需指定监控机地址与本机标识
cd /opt/monitor-agent
JAVA_OPTS="-Xmx256m" JAVA_TOOL_OPTIONS="-Dagent.server.host=<监控机IP> -Dagent.server.id=<主机名>" ./start.sh

# 3) 查看/停止
./status.sh
tail -f app.log
./stop.sh
```

- 本机无 nvidia-smi（或想演示）：追加 `-Dagent.simulate=true`
- 采集周期默认 2s，改：`-Dagent.interval.ms=5000`
- **改端口（与 server 包联动）**：服务端改了监听端口后，这里也要同步：
  `JAVA_TOOL_OPTIONS="-Dagent.server.port=<同一端口> -Dagent.server.host=<监控机IP> -Dagent.server.id=<主机名>" ./start.sh`
  > `agent.server.port` 指**中央服务的监听端口**（即 server 包的 `server.port`），两者必须一致。
- `JAVA_OPTS="-Xmx256m"`：给 agent 设堆上限（存活集很小，无需默认 1/4 内存的大堆）。不加时 `jvm.heap_used_mb` 会随 JVM 按默认 -Xmx 增长到数百 MB——这是正常堆增长不是泄漏，设上限后指标稳定在低值。
- 断线重试采用指数退避（默认 1s 起、上限 30s、每次翻倍），可调：
  `-Dagent.retry.initial.ms=1000 -Dagent.retry.max.ms=30000 -Dagent.retry.multiplier=2.0`

## 常见问题

- **面板看不到某台服务器**：检查该 agent 的 `agent.server.host` 是否指向监控机 IP、网络/防火墙（对应端口）是否放行。
- **改端口后 Agent 连不上 / 面板打不开**：多半是 `server.port` 与各 Agent 的 `agent.server.port` 没改成同一个值，或防火墙未放行新端口。
- **端口被占用启动失败**：日志报 `Address already in use`，先停掉占用该端口的进程（Linux `ss -ltnp | grep <端口>`，Windows `netstat -ano | findstr <端口>`）。
- **多台服务器**：每台 GPU 机部署一份 agent，`-Dagent.server.id` 用不同名称即可在面板上区分。
- **全部 -D 配置项**：见源码 `src/main/resources/agent.properties`、`server.properties`。
