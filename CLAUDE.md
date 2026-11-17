# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **当前状态：绿地（greenfield）项目。** 构建本文件时仓库为空，以下为已确认的架构契约（architecture contract），是一切后续编码的准绳。任何偏离需与用户确认。构建工具与框架的最终选择在脚手架搭建时确认（下方为推荐项，标注了「提议」）。

## 项目概述

服务器指标监控系统：监控多台服务器，浏览器面板实时查看指标。

本阶段核心需求（用户已确认）：
- **不持久化数据，只展示实时指标** —— 全链路实时，指标过期即弃。
- **第一阶段只监控 GPU 指标，并支持自定义指标扩展。**
- 采用 **Agent 采集**：每台被监控服务器部署一个 Agent 程序，主动上报到中央服务。

## 整体架构与数据流

单向实时流水线，无存储环节：

```
┌─ 被监控服务器 ──────────────────────────┐
│  Java Agent 进程                          │
│   ├─ Collector 集合（nvidia-smi 解析等）   │
│   └─ 上报客户端（HTTP POST / WebSocket）   │
└───────────────┬──────────────────────────┘
                │ JSON 指标帧（周期上报）
                ▼
┌─ 中央服务（纯 Netty）─────────────────────────────┐
│  ├─ 接收端点：接收各 Agent 上报            │
│  ├─ 内存注册表：每个 server 只保留最新帧    │
│  ├─ 心跳/离线判定：记录 last-seen          │
│  └─ WebSocket Hub：向浏览器客户端广播       │
└───────────────┬──────────────────────────┘
                │ WebSocket 推送（最新帧）
                ▼
┌─ 浏览器面板（纯 HTML/CSS/JS）──────────────────────┐
│  WebSocket 客户端 → 实时渲染 GPU/自定义指标│
└──────────────────────────────────────────┘
```

关键设计含义：
- **实时是本项目的第一约束**。中央服务是无状态的内存聚合器：Agent 周期上报 → 中央服务更新该 server 的最新帧 → 推送所有订阅的浏览器连接。不落库、不查询历史。
- **推送通道选 WebSocket**（Agent↔服务端、服务端↔浏览器皆可复用）。浏览器端必须实现断线重连与心跳。
- 每台 Agent 在中央服务注册，中央服务需区分**离线**（超过 N 个周期未上报）与**在线**状态，面板要有对应呈现。
- **鲁棒性**：Agent 采集与上报**解耦**——采集固定周期刷新最新帧，上报由独立线程负责。网络中断/服务端不可达时按 `agent.retry.initial.ms → max.ms` **指数退避重连**（默认 1s→2s→4s→…→30s 封顶），恢复后立即用最新帧重连。前端 WebSocket 同样实现断线退避重连。

## 技术栈（用户已选定）

- **后端**：Java + **纯 Netty**（不用 Spring 全家桶）。Netty 一个进程承担三件事：接收 Agent 上报、向浏览器 WebSocket 广播、托管前端静态资源。构建工具 Maven。**兼容 JDK 8+**（编译目标 `release 8`，上报用 JDK8 的 `HttpURLConnection`，无 Java 11+ API）。
- **前端**：**纯 HTML/CSS/JS**（不用 Vue / Vite / ECharts）。无构建步骤，由 Netty 直接托管；图表用手写 Canvas/SVG 绘制，WebSocket 用浏览器原生 API，自带断线重连。
- **GPU 数据来源**：Linux 服务器上执行 `nvidia-smi --query-gpu=... --format=csv` 并解析输出（跨机器可移植且无额外依赖），或用 JNA 绑定 NVML。Windows 开发机需注意 nvidia-smi 命令差异。

## 面板展示与操作

- **双视图**：顶栏「标准 / 精简」切换。标准视图用 Canvas 环形仪表；**精简视图**为纯文本紧凑表格（每 GPU 一行：#/型号/利用率/温度/显存/功耗），无图标、高信息密度，选择以 `localStorage` 持久化。
- **清除离线记录**：顶栏「清除离线」按钮调用 `POST /api/servers/clear-offline`，服务端删除所有离线/未连接注册项并向面板广播 `remove` 消息移除对应卡片；**在线记录保留**。
- **对外 GPU 负载接口**：`GET /api/gpu-load` 返回各服务器 GPU 负载（按 `load` 升序、最空闲在前；`?all=true` 含离线），供三方系统（如 LLM 调度路由）挑选/切换空闲服务器。`load` = 最高一张 GPU 利用率，无 GPU 为 `-1`；只读内存最新帧，不落库。契约见 README「对外 API」。

## 指标模型与自定义指标扩展（核心设计）

这是「自定义指标」需求落地的地方，所有实现都围绕它展开：

- **指标帧结构（agent 上报 → 服务端 → 前端共用的契约）**：
  ```
  server:     标识被监控服务器（host/分组）
  timestamp:  采集时间
  devices:    GPU 卡片数组（index → utilization, temperature, memory_used, power, fan…）
  metrics:    通用指标字典（name → value），供自定义指标使用
  ```
  结构需支持一台服务器多张 GPU（GPU 按 index 区分）。
- **自定义指标 = 自定义 Collector**：定义 Collector 接口/SPI（采集、返回值），Agent 周期性调用注册的所有 Collector。GPU（nvidia-smi 解析）是第一个 Collector 实现；后续 CPU/磁盘/服务探测以扩展新 Collector 的方式加入，不改动数据链路。
- 指标命名、上报频率（间隔）应在配置文件（如 agent.properties）中集中约定，便于 Agent 调整。

## 模块规划（提议）

```
server-monitor/
├── agent/      被监控端：构建每台服务器上运行的 Agent
├── server/     中央服务（纯 Netty）：接收上报 + WebSocket 广播 + 托管前端静态资源
├── web/        浏览器面板：纯 HTML/CSS/JS + Canvas（无框架、无构建）
└── 根目录      文档、部署脚本（docker-compose 等）
```

模块间只通过「指标帧 JSON」耦合，语义对齐上述指标模型。

## 常用命令

- 构建：`mvn -DskipTests package`（离线环境，依赖从 `D:\workspace\addRespos` + `D:\workspace\localRepository` 两个本地仓库解析，版本见父 POM 顶部注释）。
- 拷贝依赖到 `target/lib/`：`scripts/copy-libs.sh`（Windows 用 `.bat`）。**离线环境无法用 maven-shade / maven-dependency 插件**，thin jar 依赖 manifest Class-Path 引用 `lib/*.jar`，运行前必须执行此步。
- 启动中央服务器：`scripts/run-server.sh`（默认 http://localhost:8080/）
- 启动 Agent：`scripts/run-agent.sh`；无 nvidia-smi 用 `JAVA_TOOL_OPTIONS="-Dagent.simulate=true"`；改主机名用 `JAVA_TOOL_OPTIONS="-Dagent.server.id=web01"`；断线重试节奏用 `-Dagent.retry.initial.ms / -Dagent.retry.max.ms / -Dagent.retry.multiplier` 调整（`-D` 须在 `-jar` 前，脚本内用 `JAVA_TOOL_OPTIONS` 传入）
- 测试：`mvn test`（暂无测试类）
- 打包部署：`scripts/package.sh`（Windows 用 `.bat`）生成自包含的 `dist/agent` 与 `dist/server`；每个包含 jar + `lib/` 依赖 + 面板 `web/` + `start/stop/status.sh` 守护脚本，兼容 JDK 8+（编译目标 release 8）。部署步骤见 `scripts/DEPLOY.md`。

## 离线构建注意

本机无外网（DNS 无法解析 maven.aliyun.com / repo1.maven.org），settings.xml 把 localRepository 指向 `D:\workspace\addRespos`，父 POM 通过 `file://` 仓库追加了 `D:\workspace\localRepository`（netty-codec-http 等模块仅存在于该仓库）。联网后可将 netty/jackson 升级到最新版并移除该 file:// 仓库与 `scripts/copy-libs.*`。


## 注意
模型不支持多模态，不要提交图片。