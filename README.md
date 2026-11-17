# 服务器监控

浏览器端实时查看的多服务器指标监控：**Java Agent 采集 → 纯 Netty 中央服务 → 纯前端面板（WebSocket 推送，不落库）**。

## 模块

| 模块 | 职责 |
|---|---|
| `common` | 指标帧模型 `MetricFrame` / `GpuInfo` + JSON 工具（Agent/服务器/前端共用契约） |
| `agent` | 被监控端：GPU 采集器（nvidia-smi 解析）+ 自定义指标 SPI + HTTP 上报（采集/上报解耦，断线指数退避重连） |
| `server` | 纯 Netty：接收上报（`/report`）、WebSocket 广播（`/ws`）、托管 `web/` 静态面板、清空离线记录（`POST /api/servers/clear-offline`） |
| `web` | 纯 HTML/CSS/JS 面板：Canvas 环形仪表 + 精简表格双视图 + 断线重连，无框架、无构建 |

## 构建

```bash
mvn -DskipTests package
# 离线环境：把依赖 jar 拷入各模块 target/lib/（本机无法用 shade/dependency 插件）
scripts/copy-libs.sh        # Windows 用 scripts/copy-libs.bat
```

> 编译目标 `release 8`，产物兼容 **JDK 8 及以上**（上报用 JDK8 的 `HttpURLConnection`，无 Java 11+ API）。

## 运行

```bash
# 1. 启动中央服务（默认 8080，同时提供上报接口 / 面板 WebSocket / 静态前端）
scripts/run-server.sh       # Windows 用 scripts/run-server.bat

# 2. 启动 Agent（采集本机 GPU 并上报；无 nvidia-smi 时用 JAVA_TOOL_OPTIONS="-Dagent.simulate=true" 模拟）
scripts/run-agent.sh
```

浏览器打开 <http://localhost:8080/> 查看实时面板。

## 面板

- **标准 / 精简双视图**：顶栏切换。标准视图用 Canvas 环形仪表展示利用率/温度/显存/功耗；精简视图用纯文本紧凑表格（每 GPU 一行），无图标、信息密度更高。选择以 `localStorage` 持久化，刷新保留。
- **清除离线记录**：顶栏「清除离线」按钮，删除所有离线/未连接的服务器记录（在线的不受影响），避免历史残留卡片堆积。
- 每张服务器卡片右上角显示在线/离线状态与最近上报时间；断线自动退避重连。

## 打包部署（生成两个自包含发布包）

```bash
scripts/package.sh        # Windows cmd 用 scripts\package.bat
```

在仓库根目录生成两个可直接上传的目录：

- **`dist/agent`**（部署到每台被监控的 GPU 服务器）
- **`dist/server`**（部署到监控中心机器）

每个包自包含：`jar + lib/ 依赖 + web 面板 + start/stop/status 脚本`，兼容 JDK 8+。详见 [scripts/DEPLOY.md](scripts/DEPLOY.md)。

```bash
# 监控中心机
scp -r dist/server root@<监控机IP>:/opt/monitor-server/
cd /opt/monitor-server && ./start.sh

# 每台 GPU 机
scp -r dist/agent root@<GPU机IP>:/opt/monitor-agent/
cd /opt/monitor-agent && JAVA_TOOL_OPTIONS="-Dagent.server.host=<监控机IP> -Dagent.server.id=<主机名>" ./start.sh
```

## 配置

所有键可用系统属性覆盖（Java 的 `-D` 须放在 `-jar` 前；通过脚本运行时用 `JAVA_TOOL_OPTIONS` 环境变量传入），含义见各模块 `src/main/resources/*.properties`。

常用项：
```bash
# 面板上显示的主机名
JAVA_TOOL_OPTIONS="-Dagent.server.id=web01" scripts/run-agent.sh
# 用模拟 GPU 数据（开发 / 演示，本机无 nvidia-smi 时）
JAVA_TOOL_OPTIONS="-Dagent.simulate=true" scripts/run-agent.sh
# 服务端口
JAVA_TOOL_OPTIONS="-Dserver.port=9090" scripts/run-server.sh
# 断线重试节奏：首次失败后等待 / 退避上限 / 每次翻倍倍数（默认 1000ms / 30000ms / 2.0）
JAVA_TOOL_OPTIONS="-Dagent.retry.initial.ms=1000 -Dagent.retry.max.ms=30000 -Dagent.retry.multiplier=2.0" scripts/run-agent.sh
```
