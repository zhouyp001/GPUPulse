package com.monitor.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.server.ServerRegistry;
import com.monitor.util.Json;

import java.util.Iterator;

/**
 * MCP 能力目录：工具 / 资源 / 提示词的定义与执行器。
 * 全部只读，数据源为 {@link ServerRegistry} 现有方法，不改动注册表内部实现。
 * 参数不合法 / 目标不存在时抛 {@link IllegalArgumentException}，由 {@link McpServer} 转为 JSON-RPC -32602。
 */
public class McpCapabilities {
    private final ServerRegistry registry;

    public McpCapabilities(ServerRegistry registry) {
        this.registry = registry;
    }

    // ---------- 工具 ----------

    public ObjectNode tools() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode tools = root.putArray("tools");

        ObjectNode listTool = tools.addObject();
        listTool.put("name", "list_servers");
        listTool.put("description", "列出被监控服务器（默认仅在线），每台含在线状态、GPU 数量与负载 load（最高单卡利用率，值越小越空闲）。");
        listTool.set("inputSchema", objectSchema(
                boolProp("include_offline", "是否包含离线服务器，默认 false")));

        ObjectNode getTool = tools.addObject();
        getTool.put("name", "get_server");
        getTool.put("description", "获取指定被监控服务器的最新指标帧：GPU 明细数组 + 通用指标字典（metrics）。");
        getTool.set("inputSchema", objectSchema(
                stringProp("server", "被监控服务器 id（如 web01）")));

        ObjectNode gpuTool = tools.addObject();
        gpuTool.put("name", "gpu_load");
        gpuTool.put("description", "各服务器 GPU 负载，按 load 升序（最空闲在前），与 GET /api/gpu-load 一致；离线服务器保留上次帧的 load。");
        gpuTool.set("inputSchema", objectSchema(
                boolProp("include_offline", "是否包含离线服务器，默认 false")));
        return root;
    }

    /**
     * 执行工具调用，返回 MCP 结果对象（content 文本 + structuredContent）。
     *
     * @param name 工具名
     * @param args 参数对象（可空）
     */
    public ObjectNode callTool(String name, ObjectNode args) {
        JsonNode payload;
        if ("list_servers".equals(name)) {
            payload = listServers(includeOffline(args));
        } else if ("gpu_load".equals(name)) {
            payload = registry.gpuLoad(includeOffline(args));
        } else if ("get_server".equals(name)) {
            payload = serverFrame(requireString(args, "server"));
        } else {
            throw new IllegalArgumentException("unknown tool: " + name);
        }
        ObjectNode result = Json.MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", payload.toString());
        result.put("isError", false);
        result.set("structuredContent", payload);
        return result;
    }

    // ---------- 资源 ----------

    public ObjectNode resources() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode resources = root.putArray("resources");
        resources.add(resource("monitor://servers", "服务器清单",
                "全部被监控服务器的在线状态与 GPU 负载（含离线）。"));

        // 动态枚举当前已知服务器，便于客户端先 list 再 read
        JsonNode servers = registry.snapshot().path("servers");
        Iterator<String> it = servers.fieldNames();
        while (it.hasNext()) {
            String id = it.next();
            resources.add(resource("monitor://servers/" + id, "服务器 " + id + " 最新指标帧", null));
        }

        ArrayNode templates = root.putArray("resourceTemplates");
        ObjectNode t = templates.addObject();
        t.put("uriTemplate", "monitor://servers/{id}");
        t.put("name", "服务器最新指标帧");
        t.put("mimeType", "application/json");
        t.put("description", "指定被监控服务器的最新指标帧：GPU 明细 + metrics 字典。");
        return root;
    }

    public ObjectNode readResource(String uri) {
        ObjectNode item;
        if ("monitor://servers".equals(uri)) {
            item = contentItem(uri, listServers(true).toString());
        } else if (uri.startsWith("monitor://servers/")) {
            String id = uri.substring("monitor://servers/".length());
            item = contentItem(uri, serverFrame(id).toString());
        } else {
            throw new IllegalArgumentException("unknown resource: " + uri);
        }
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.putArray("contents").add(item);
        return root;
    }

    // ---------- 提示词 ----------

    public ObjectNode prompts() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode prompts = root.putArray("prompts");

        ObjectNode p1 = prompts.addObject();
        p1.put("name", "recommend_idle_server");
        p1.put("description", "推荐一台最空闲的被监控服务器（load 最低），供调度使用。");
        p1.putArray("arguments");

        ObjectNode p2 = prompts.addObject();
        p2.put("name", "gpu_health_summary");
        p2.put("description", "汇总指定服务器的 GPU 健康状态（利用率 / 温度 / 显存 / 功耗）。");
        ArrayNode args2 = p2.putArray("arguments");
        ObjectNode a = args2.addObject();
        a.put("name", "server");
        a.put("description", "被监控服务器 id");
        a.put("required", true);
        return root;
    }

    public ObjectNode getPrompt(String name, ObjectNode args) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        ArrayNode messages = root.putArray("messages");
        if ("recommend_idle_server".equals(name)) {
            root.put("description", "推荐最空闲的一台服务器。");
            messages.add(systemMessage("你是服务器监控调度助手。调用 gpu_load 工具（include_offline=false）获取各服务器负载，"
                    + "选择 load 最小（最空闲）的一台；先给结论，再给一句话理由（引用该服务器的 load 值）。"));
        } else if ("gpu_health_summary".equals(name)) {
            String server = requireString(args, "server");
            root.put("description", "综合汇总服务器 " + server + " 的 GPU 健康状态。");
            messages.add(systemMessage("作为服务器监控助手，调用 get_server 工具查询服务器 \"" + server
                    + "\" 的最新指标帧。汇总每张 GPU 的利用率、温度、显存占用与功耗；"
                    + "对利用率>75%、温度>80°C、显存占用>90% 的 GPU 标注异常。"));
        } else {
            throw new IllegalArgumentException("unknown prompt: " + name);
        }
        return root;
    }

    // ---------- 内部实现 ----------

    private ArrayNode listServers(boolean includeOffline) {
        JsonNode servers = registry.gpuLoad(includeOffline).path("servers");
        ArrayNode out = Json.MAPPER.createArrayNode();
        for (JsonNode n : servers) {
            ObjectNode o = Json.MAPPER.createObjectNode();
            o.put("server", n.path("server").asText());
            o.put("online", n.path("online").asBoolean());
            o.put("lastSeen", n.path("lastSeen").asLong());
            o.put("gpuCount", n.path("gpuCount").asInt());
            o.put("load", n.path("load").asDouble(-1));
            out.add(o);
        }
        return out;
    }

    /** 单台服务器最新帧：抽出 frame 里的 gpus / metrics，附在线状态。未知 id 抛 IllegalArgumentException。 */
    private ObjectNode serverFrame(String id) {
        JsonNode node = registry.snapshot().path("servers").path(id);
        if (node.isMissingNode() || node.isNull()) {
            throw new IllegalArgumentException("unknown server: " + id);
        }
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("server", id);
        out.put("online", node.path("online").asBoolean());
        out.put("lastSeen", node.path("lastSeen").asLong());
        JsonNode frame = node.path("frame");
        if (frame.isNull() || frame.isMissingNode()) {
            out.put("timestamp", 0L);
            out.putArray("gpus");
            out.putObject("metrics");
        } else {
            out.put("timestamp", frame.path("timestamp").asLong());
            out.set("gpus", frame.path("gpus"));
            out.set("metrics", frame.path("metrics"));
        }
        return out;
    }

    private static boolean includeOffline(ObjectNode args) {
        return args != null && args.path("include_offline").asBoolean(false);
    }

    private static String requireString(ObjectNode args, String field) {
        if (args == null || !args.has(field) || !args.path(field).isTextual()
                || args.path(field).asText().trim().isEmpty()) {
            throw new IllegalArgumentException("missing required argument: " + field);
        }
        return args.path(field).asText();
    }

    // ---------- JSON 构造小工具 ----------

    /** JSON Schema 对象骨架；props 为带 _name/_required 标记的内部属性节点，装配时剥掉标记。 */
    private static ObjectNode objectSchema(ObjectNode... props) {
        ObjectNode schema = Json.MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (ObjectNode prop : props) {
            String name = prop.path("_name").asText();
            boolean req = prop.path("_required").asBoolean(false);
            prop.remove("_name");
            prop.remove("_required");
            properties.set(name, prop);
            if (req) {
                required.add(name);
            }
        }
        schema.put("additionalProperties", false);
        if (required.size() == 0) {
            schema.remove("required");
        }
        return schema;
    }

    private static ObjectNode boolProp(String name, String description) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("type", "boolean");
        p.put("description", description);
        p.put("_name", name);
        p.put("_required", false);
        return p;
    }

    private static ObjectNode stringProp(String name, String description) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("type", "string");
        p.put("description", description);
        p.put("_name", name);
        p.put("_required", true);
        return p;
    }

    private static ObjectNode resource(String uri, String name, String description) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("uri", uri);
        n.put("name", name);
        n.put("mimeType", "application/json");
        if (description != null) {
            n.put("description", description);
        }
        return n;
    }

    private static ObjectNode contentItem(String uri, String text) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("uri", uri);
        n.put("mimeType", "application/json");
        n.put("text", text);
        return n;
    }

    private static ObjectNode systemMessage(String text) {
        ObjectNode msg = Json.MAPPER.createObjectNode();
        msg.put("role", "system");
        ObjectNode content = msg.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return msg;
    }
}
