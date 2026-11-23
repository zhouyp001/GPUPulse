# monitor MCP 请求介绍

> 本文介绍 **monitor** MCP 端点对外提供的**请求 / 响应报文**：三类抽象（Tools / Resources / Prompts）各自的方法、入参与出参，附实测抓取的报文示例。
> 服务端实现：`server-monitor` v0.1.0 · 端点 `http://localhost:4321/mcp`。

---

## 公共部分

三类共用同一端点，**JSON-RPC 2.0 over HTTP POST**（`.mcp.json` 中 `type: "http"`，响应是单条 JSON，不做 SSE 分帧）：

```
POST http://localhost:4321/mcp
Content-Type: application/json
```

请求体结构固定：

```json
{ "jsonrpc": "2.0", "id": N, "method": "<方法>", "params": { } }
```

首次通信先 `initialize` 握手：

```json
{"jsonrpc":"2.0","id":1,"method":"initialize",
 "params":{"protocolVersion":"2025-06-18","capabilities":{},
           "clientInfo":{"name":"any-client","version":"1.0"}}}
```

握手成功返回 `serverInfo` + `capabilities`（三类全开）。服务端**无状态**：不强制会话令牌，后续每条请求可独立直调；`protocolVersion` 支持 `2024-11-05` / `2025-06-18`。

---

## 1. Tools

### ① 列出工具（`tools/list`）

```json
{"jsonrpc":"2.0","id":10,"method":"tools/list","params":{}}
```

响应（3 个工具，含 `inputSchema`）：

```json
{"result":{"tools":[
  {"name":"list_servers",
   "description":"列出被监控服务器（默认仅在线），每台含在线状态、GPU 数量与负载 load。",
   "inputSchema":{"type":"object","properties":{"include_offline":{"type":"boolean","description":"是否包含离线服务器，默认 false"}},"additionalProperties":false}},
  {"name":"get_server",
   "description":"获取指定被监控服务器的最新指标帧：GPU 明细数组 + 通用指标字典（metrics）。",
   "inputSchema":{"type":"object","properties":{"server":{"type":"string","description":"被监控服务器 id（如 151）"}},"required":["server"],"additionalProperties":false}},
  {"name":"gpu_load",
   "description":"各服务器 GPU 负载，按 load 升序（最空闲在前）；离线服务器保留上次帧的 load。",
   "inputSchema":{"type":"object","properties":{"include_offline":{"type":"boolean","description":"是否包含离线服务器，默认 false"}},"additionalProperties":false}}
]}}
```

> schema 里 `required` 只对 `get_server.server` 出现一次；`include_offline` 对 `list_servers` / `gpu_load` 均为可选，缺省即视为 `false`。

### ② 执行工具（`tools/call`，`name` + `arguments`）

```json
{"jsonrpc":"2.0","id":11,
 "method":"tools/call",
 "params":{"name":"get_server","arguments":{"server":"151"}}}
```

响应（HTTP 200，`content-type: application/json`）：

```json
{"jsonrpc":"2.0","id":11,"result":{
  "content":[{"type":"text","text":"{\"server\":\"151\",\"online\":true,\"gpus\":[{...}],\"metrics\":{...}}"}],
  "isError": false,
  "structuredContent": { "server":"151","online":true,"gpus":[...],"metrics":{...} }
}}
```

`get_server` 单机帧的 `gpus[]` 单卡字段：`utilization`（利用率 %）、`temperature`（℃）、`memoryUsed` / `memoryTotal`（MB）、`powerDraw`（W）等。

**报文要点**
- 数据在 `result.structuredContent`；`content[0].text` 是它的 JSON 字符串副本。
- 大响应单帧直回：`gpu_load` 全集群 6 机 ≈ **16771 字节**，因走普通 http（非 streamable）**不拆 SSE**，一条 JSON 直接回完（对比 streamableHttp 会是 `text/event-stream` 分帧）。

---

## 2. Resources

### ① 列出资源（`resources/list`）

```json
{"jsonrpc":"2.0","id":30,"method":"resources/list","params":{}}
```

响应：

```json
{"result":{
  "resources":[
    {"uri":"monitor://servers","name":"服务器清单","mimeType":"application/json","description":"全部被监控服务器的在线状态与 GPU 负载（含离线）。"},
    {"uri":"monitor://servers/132","name":"服务器 132 最新指标帧","mimeType":"application/json"},
    {"uri":"monitor://servers/530","name":"服务器 530 最新指标帧","mimeType":"application/json"},
    {"uri":"monitor://servers/537","name":"服务器 537 最新指标帧","mimeType":"application/json"},
    {"uri":"monitor://servers/151","name":"服务器 151 最新指标帧","mimeType":"application/json"},
    {"uri":"monitor://servers/152","name":"服务器 152 最新指标帧","mimeType":"application/json"},
    {"uri":"monitor://servers/131","name":"服务器 131 最新指标帧","mimeType":"application/json"}
  ],
  "resourceTemplates":[
    {"uriTemplate":"monitor://servers/{id}","name":"服务器最新指标帧","mimeType":"application/json","description":"指定被监控服务器的最新指标帧：GPU 明细 + metrics 字典。"}
  ]
}}
```

> 单机资源 uri（132/530/537/151/152/131）会随上报动态变化，实际清单以每次 `resources/list` 返回为准。

⚠️ **没有独立的 `resources/templates/list` 方法**（实测返回 JSON-RPC `method not found`）。模板仅内嵌在 `resources/list` 响应的 `resourceTemplates` 字段里——这也是为何客户端里只见 7 个资源（`monitor://servers` + 6 台动态枚举），模板不是独立资源。

### ② 读取资源（`resources/read`，`uri` 为寻址键）

```json
{"jsonrpc":"2.0","id":20,"method":"resources/read","params":{"uri":"monitor://servers/152"}}
```

响应：

```json
{"result":{"contents":[
  {"uri":"monitor://servers/152","mimeType":"application/json",
   "text":"{\"server\":\"152\",\"online\":true,\"lastSeen\":1795417498286,\"gpus\":[{\"index\":0,\"utilization\":0,\"temperature\":19,\"memoryUsed\":8794,\"powerDraw\":107.45},...],\"metrics\":{\"jvm.heap_used_mb\":64.8}}"}
]}}
```

单机 resource 的载荷语义与 `get_server` 工具一致；`monitor://servers` 则是全集群精简数组（server / online / lastSeen / gpuCount / load，无逐卡明细）。

---

## 3. Prompts

### ① 列出提示词（`prompts/list`）

```json
{"jsonrpc":"2.0","id":31,"method":"prompts/list","params":{}}
```

响应：

```json
{"result":{"prompts":[
  {"name":"recommend_idle_server","description":"推荐一台最空闲的被监控服务器（load 最低），供调度使用。","arguments":[]},
  {"name":"gpu_health_summary","description":"汇总指定服务器的 GPU 健康状态（利用率/温度/显存/功耗）。",
   "arguments":[{"name":"server","description":"被监控服务器 id","required":true}]}
]}}
```

### ② 获取提示词（`prompts/get`，`arguments` 可模板化）

带参版本：

```json
{"jsonrpc":"2.0","id":22,"method":"prompts/get",
 "params":{"name":"gpu_health_summary","arguments":{"server":"131"}}}
```

响应：

```json
{"result":{
  "description":"综合汇总服务器 131 的 GPU 健康状态。",
  "messages":[{"role":"system","content":{"type":"text","text":
   "作为服务器监控助手，调用 get_server 工具查询服务器 \"131\" 的最新指标帧。汇总每张 GPU 的利用率、温度、显存占用与功耗；对利用率>75%、温度>80°C、显存占用>90% 的 GPU 标注异常。"}}]
}}
```

可见 `arguments.server` 会被**实例化**进 system 指令（131 直接内嵌成目标服务器），使返回的 `messages` 针对指定服务器。

---

## 4. 抽象一览

| 抽象 | 核心方法 | Key 参数 | 响应载荷 | 本质 |
|---|---|---|---|---|
| **Tools** | `tools/list` / `tools/call` | `name` + `arguments` | `result.structuredContent` | 实时执行动作，无固定地址 |
| **Resources** | `resources/list` / `resources/read` | `uri`（如 `monitor://servers/151`） | `result.contents[].text` | URI 寻址的结构化快照 |
| **Prompts** | `prompts/list` / `prompts/get` | `name` + 可选 `arguments` | `result.messages[]`（system 指令） | 可复用的行为模板 |

三类共享同一数据源（server → gpus → metrics）。

---

## 5. 报文要点

| # | 结论 | 出处 |
|---|---|---|
| 1 | `resources/templates/list` **不存在**；模板仅经 `resources/list.resourceTemplates` 暴露 | §2.① |
| 2 | 大响应单帧直回，不拆 SSE（因 `type: "http"`）：`gpu_load` ≈ 16 KB | §1.② |
| 3 | `include_offline` 对 `list_servers` / `gpu_load` 为可选，缺省仅在线；服务端**读取**它——传 `true` 含离线，且离线服务器保留上次帧的 `load`（非 -1） | §1 schema |
| 4 | `get_server` / 资源单卡显存字段为 `memoryUsed` / `memoryTotal`（MB） | §1.② / §2.② |
| 5 | `list_servers` 返回 JSON **数组**（server/online/lastSeen/gpuCount/load）；若客户端严格校验 record 可能拒收，可用 `gpu_load` 替代 | `tools/call list_servers` |

> 领域术语（server / load / 卡字段 / metrics）见根目录 `GLOSSARY.md`。
