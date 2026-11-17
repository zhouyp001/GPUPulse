# 服务器监控

浏览器端实时查看的多服务器指标监控：**Java Agent 采集 → 纯 Netty 中央服务 → 纯前端面板（WebSocket 推送，不落库）**。

## 模块

| 模块 | 职责 |
|---|---|
| `common` | 指标帧模型 `MetricFrame` / `GpuInfo` + JSON 工具（Agent/服务器/前端共用契约） |
| `agent` | 被监控端：GPU 采集器（nvidia-smi 解析）+ 自定义指标 SPI + HTTP 上报（采集/上报解耦，断线指数退避重连） |
| `server` | 纯 Netty：接收上报（`/report`）、WebSocket 广播（`/ws`）、托管 `web/` 静态面板、清空离线记录（`POST /api/servers/clear-offline`）、GPU 负载查询（`GET /api/gpu-load`） |
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

## 对外 API（供三方系统集成）

中央服务除面板外，还提供 HTTP 接口供外部系统调用（只读内存最新帧，不落库）。

### GET /api/gpu-load —— 各服务器 GPU 负载（按空闲度排序）

供三方系统按负载挑选 / 切换服务器（如 **LLM 调度路由**：先查哪台机器最空闲，再调用该机器上的推理服务）。

| 查询参数 | 默认 | 说明 |
|---|---|---|
| `all` | `false` | `true` 时包含离线服务器（`online:false`、`load:-1` 排最后），否则只返回在线 |

响应（`servers` 按 `load` 从低到高，**最空闲在前**）：

```json
{
  "type": "gpu-load",
  "timestamp": 1737000000000,
  "servers": [
    {
      "server": "web01",
      "online": true,
      "lastSeen": 1737000000000,
      "gpuCount": 2,
      "load": 35.0,
      "utilizationAvg": 30.0,
      "utilizationMax": 35.0,
      "memoryUsedGb": 6.0,
      "memoryTotalGb": 16.0,
      "freeMemoryGb": 10.0,
      "gpus": [
        {
          "index": 0,
          "name": "NVIDIA A100",
          "utilization": 35.0,
          "temperature": 62.0,
          "memoryUsedMb": 4096,
          "memoryTotalMb": 8192,
          "freeMemoryMb": 4096,
          "powerDraw": 180.0
        }
      ]
    }
  ]
}
```

字段说明：

- `load`：该服务器**最高一张 GPU 的利用率**（任何一张卡忙即视为忙），作为空闲度主排序依据；无 GPU 时为 `-1`。
- `utilizationAvg` / `utilizationMax`：所有 GPU 利用率均值 / 最大值。
- `memoryUsedGb` / `memoryTotalGb` / `freeMemoryGb`：整机显存（MB → GB）。
- `gpus[]`：每张 GPU 明细，显存字段为 MB（`memoryUsedMb` / `memoryTotalMb` / `freeMemoryMb`）。

示例（取最空闲的在线服务器）：

```bash
curl -s http://<监控机IP>:8080/api/gpu-load | jq -r '.servers[0].server'
```

### 其他接口

- `GET /api/servers`：全量快照（调试 / 非 WebSocket 客户端用）。
- `POST /api/servers/clear-offline`：清空所有离线记录（在线保留）。

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

### 自定义端口（详细说明）

**中央服务只监听一个端口**（`server.port`，默认 `8080`），这一个端口同时承载三件事：

1. Agent 上报接口（HTTP `POST /report`）
2. 面板 WebSocket（`/ws`）
3. 静态前端页面

所以**改端口只需改服务端这一处**，上报、广播、面板会一起切到新端口。

**Agent 端不监听端口**，只配置要上报到的中央服务地址：`agent.server.host` + `agent.server.port`（默认 `127.0.0.1:8080`）。这里的 `agent.server.port` 指的就是**中央服务的监听端口**，不是 Agent 自己的端口。

> ⚠️ **关键**：改了服务端监听端口，就**必须**把每个 Agent 的 `agent.server.port` 改成同一个值，否则 Agent 往旧端口上报（失败、退避重连），浏览器也访问不到面板。

#### 方式一：启动时用 `-D` 覆盖（临时调试最方便）

```bash
# ① 服务端改监听端口
JAVA_TOOL_OPTIONS="-Dserver.port=9090" scripts/run-server.sh

# ② 每个 Agent 同步改目标端口（host 也要指向监控机，跨机时别用 127.0.0.1）
JAVA_TOOL_OPTIONS="-Dagent.server.port=9090 -Dagent.server.host=<监控机IP>" scripts/run-agent.sh

# ③ 浏览器访问新端口
# http://localhost:9090/   （本机） 或  http://<监控机IP>:9090/
```

#### 方式二：改配置文件后重新打包（长期部署推荐）

编辑 `server/src/main/resources/server.properties` 与 `agent/src/main/resources/agent.properties` 中的端口，然后 `mvn -DskipTests package` 重新打包。所有 Agent 与浏览器都固定用新端口，无需每次启动带参数。打包部署后各端口的设置见 [scripts/DEPLOY.md](scripts/DEPLOY.md)。

#### 场景对照表

| 场景 | 服务端 `server.port` | 每个 Agent `agent.server.port` | 浏览器地址 |
|---|---|---|---|
| 默认 | （不设，8080） | （不设，8080） | `http://<监控机IP>:8080/` |
| 换端口 | `-Dserver.port=9090` | `-Dagent.server.port=9090` | `http://<监控机IP>:9090/` |

#### 注意

- **端口被占用**：启动失败，日志报 `Address already in use`，先确认该端口没被其他进程占用（Windows 用 `netstat -ano | findstr :8080`，Linux 用 `ss -ltnp | grep 8080`）。
- **防火墙 / 安全组**：需放行该端口的 TCP 入站，否则 Agent 连不上、浏览器打不开。
- 服务端只有一个端口，**不存在**「上报端口」和「面板端口」分开配的情况；如果内网/外网需要不同端口，那是网络层端口转发（NAT / 反向代理）的事，与应用配置无关。
