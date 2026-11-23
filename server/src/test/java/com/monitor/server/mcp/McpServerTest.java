package com.monitor.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.model.GpuInfo;
import com.monitor.model.MetricFrame;
import com.monitor.server.ServerRegistry;
import com.monitor.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP JSON-RPC 面单测：不依赖 Netty，直接对 {@link McpServer} 喂 JSON 文本断言全链路。
 * 覆盖 initialize / tools / resources / prompts / ping / 错误码 / 通知无响应。
 */
class McpServerTest {
    private ServerRegistry registry;
    private McpServer server;

    @BeforeEach
    void setUp() {
        registry = new ServerRegistry();
        // web01：两张卡，利用率 80 / 30 → load = 80
        registry.update(frameOf("web01"));
        // web02：一张卡，利用率 20 → load = 20
        MetricFrame f2 = frameOf("web02");
        f2.gpus.clear();
        f2.gpus.add(new GpuInfo(0, "A100", 20, 50, 512, 8192, 100));
        registry.update(f2);
        server = new McpServer(new McpCapabilities(registry));
    }

    private static MetricFrame frameOf(String id) {
        MetricFrame f = new MetricFrame(id, System.currentTimeMillis());
        f.gpus.add(new GpuInfo(0, "A100", 80, 70, 1024, 8192, 300));
        f.gpus.add(new GpuInfo(1, "A100", 30, 55, 2048, 8192, 150));
        f.metrics.put("jvm.heap_used_mb", 128.5);
        return f;
    }

    private JsonNode session(String method, String params) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method
                + "\",\"params\":" + params + "}";
        return parse(server.handle(body));
    }

    /** 测试助手：对固定 JSON 做解析（readTree 的受检异常包装为运行时异常）。 */
    private static JsonNode parse(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("parse failed: " + json, e);
        }
    }

    private static JsonNode resultOf(JsonNode resp) {
        return resp.path("result");
    }

    private static JsonNode errorOf(JsonNode resp) {
        return resp.path("error");
    }

    private static int errorCode(JsonNode resp) {
        return errorOf(resp).path("code").asInt();
    }

    // ---------- initialize / ping ----------

    @Test
    void initializeReturnsLatestVersionAndCapabilities() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{}}}";
        JsonNode resp = parse(server.handle(req));
        assertEquals("2.0", resp.path("jsonrpc").asText());
        assertEquals("2025-06-18", resultOf(resp).path("protocolVersion").asText());
        assertTrue(resultOf(resp).path("capabilities").has("tools"));
        assertTrue(resultOf(resp).path("capabilities").has("resources"));
        assertTrue(resultOf(resp).path("capabilities").has("prompts"));
        assertEquals("server-monitor", resultOf(resp).path("serverInfo").path("name").asText());
    }

    @Test
    void initializeEchoes2024VersionWhenClientRequestsIt() {
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{}}}";
        JsonNode resp = parse(server.handle(req));
        assertEquals("2024-11-05", resultOf(resp).path("protocolVersion").asText());
    }

    @Test
    void pingReturnsEmptyResult() {
        JsonNode resp = session("ping", "{}");
        assertFalse(resultOf(resp).isMissingNode());
        assertEquals(0, resultOf(resp).size());
    }

    // ---------- tools ----------

    @Test
    void toolsListExposesThreeTools() {
        JsonNode tools = resultOf(session("tools/list", "{}")).path("tools");
        assertEquals(3, tools.size());
        assertTrue(tools.toString().contains("\"list_servers\""));
        assertTrue(tools.toString().contains("\"get_server\""));
        assertTrue(tools.toString().contains("\"gpu_load\""));
    }

    @Test
    void callListServersSortedByIdleness() {
        JsonNode payload = resultOf(session("tools/call",
                "{\"name\":\"list_servers\"}")).path("structuredContent");
        assertFalse(resultOf(session("tools/call", "{\"name\":\"list_servers\"}")).path("isError").asBoolean());
        // 默认仅在线，web02 更空闲在前（load 20 < 80）
        assertEquals("web02", payload.path(0).path("server").asText());
        assertEquals(20.0, payload.path(0).path("load").asDouble(), 0.01);
        assertEquals("web01", payload.path(1).path("server").asText());
        assertEquals(80.0, payload.path(1).path("load").asDouble(), 0.01);
    }

    @Test
    void callListServersIncludeOfflineKeepsRetainedLoad() {
        // 将 web02 标记离线：注册表保留其 last-frame load=20（javadoc 称 -1，实现以保存帧为准——如实暴露）
        registry.markOffline(System.currentTimeMillis() + 100_000, 1000);
        JsonNode payload = resultOf(session("tools/call",
                "{\"name\":\"list_servers\",\"arguments\":{\"include_offline\":true}}"))
                .path("structuredContent");
        assertTrue(payload.toString().contains("\"online\":false"));
        for (JsonNode n : payload) {
            if ("web02".equals(n.path("server").asText())) {
                assertEquals(false, n.path("online").asBoolean());
                assertEquals(20.0, n.path("load").asDouble(), 0.01);
            }
        }
    }

    @Test
    void callGpuLoadSameShapeAsHttpApi() {
        JsonNode payload = resultOf(session("tools/call",
                "{\"name\":\"gpu_load\"}")).path("structuredContent");
        // 调用结果与 GET /api/gpu-load 同构（type/timestamp/servers），servers 按 load 升序
        assertEquals("gpu-load", payload.path("type").asText());
        assertEquals("web02", payload.path("servers").path(0).path("server").asText());
        assertEquals("web01", payload.path("servers").path(1).path("server").asText());
        assertEquals(1, payload.path("servers").path(0).path("gpuCount").asInt());
        assertEquals(2, payload.path("servers").path(1).path("gpuCount").asInt());
    }

    @Test
    void callGetServerReturnsFrameContentAndStructured() {
        JsonNode resp = session("tools/call",
                "{\"name\":\"get_server\",\"arguments\":{\"server\":\"web01\"}}");
        JsonNode structured = resultOf(resp).path("structuredContent");
        assertFalse(resultOf(resp).path("isError").asBoolean());
        assertEquals("web01", structured.path("server").asText());
        assertTrue(structured.path("online").asBoolean());
        assertEquals(2, structured.path("gpus").size());
        assertEquals(128.5, structured.path("metrics").path("jvm.heap_used_mb").asDouble(), 0.01);
        // 文本内容可单独解析为同一 JSON
        String text = resultOf(resp).path("content").path(0).path("text").asText();
        assertEquals("web01", parse(text).path("server").asText());
    }

    @Test
    void callGetServerUnknownIdIsInvalidParams() {
        JsonNode resp = session("tools/call",
                "{\"name\":\"get_server\",\"arguments\":{\"server\":\"ghost\"}}");
        assertEquals(-32602, errorCode(resp));
    }

    @Test
    void callGetServerMissingArgIsInvalidParams() {
        JsonNode resp = session("tools/call", "{\"name\":\"get_server\"}");
        assertEquals(-32602, errorCode(resp));
    }

    @Test
    void callUnknownToolIsInvalidParams() {
        JsonNode resp = session("tools/call", "{\"name\":\"drop_table\"}");
        assertEquals(-32602, errorCode(resp));
    }

    // ---------- resources ----------

    @Test
    void resourcesListEnumeratesKnownServersAndTemplate() {
        JsonNode result = resultOf(session("resources/list", "{}"));
        assertTrue(result.path("resources").toString().contains("\"monitor://servers\""));
        assertTrue(result.path("resources").toString().contains("\"monitor://servers/web01\""));
        assertTrue(result.path("resources").toString().contains("\"monitor://servers/web02\""));
        assertEquals("monitor://servers/{id}",
                result.path("resourceTemplates").path(0).path("uriTemplate").asText());
    }

    @Test
    void readServerListResourceReturnsJsonArrayText() {
        JsonNode resp = session("resources/read", "{\"uri\":\"monitor://servers\"}");
        String text = resultOf(resp).path("contents").path(0).path("text").asText();
        JsonNode arr = parse(text);
        assertTrue(arr.isArray());
        assertEquals("application/json", resultOf(resp).path("contents").path(0).path("mimeType").asText());
    }

    @Test
    void readServerResourceByUriReturnsFrame() {
        JsonNode resp = session("resources/read", "{\"uri\":\"monitor://servers/web01\"}");
        String text = resultOf(resp).path("contents").path(0).path("text").asText();
        assertEquals("web01", parse(text).path("server").asText());
        assertEquals(2, parse(text).path("gpus").size());
    }

    @Test
    void readUnknownResourceIsInvalidParams() {
        JsonNode resp = session("resources/read", "{\"uri\":\"file:///etc/passwd\"}");
        assertEquals(-32602, errorCode(resp));
    }

    // ---------- prompts ----------

    @Test
    void promptsListExposesTwoPrompts() {
        JsonNode prompts = resultOf(session("prompts/list", "{}")).path("prompts");
        assertEquals(2, prompts.size());
        assertTrue(prompts.toString().contains("\"recommend_idle_server\""));
        assertTrue(prompts.toString().contains("\"gpu_health_summary\""));
    }

    @Test
    void getPromptRecommendIdleServerHasMessages() {
        JsonNode resp = session("prompts/get", "{\"name\":\"recommend_idle_server\"}");
        assertTrue(resultOf(resp).path("messages").size() > 0);
    }

    @Test
    void getPromptGpuSummaryRequiresServerArg() {
        JsonNode resp = session("prompts/get", "{\"name\":\"gpu_health_summary\",\"arguments\":{\"server\":\"web01\"}}");
        assertTrue(resultOf(resp).path("messages").size() > 0);
        JsonNode bad = session("prompts/get", "{\"name\":\"gpu_health_summary\"}");
        assertEquals(-32602, errorCode(bad));
    }

    @Test
    void getUnknownPromptIsInvalidParams() {
        JsonNode resp = session("prompts/get", "{\"name\":\"sing_a_song\"}");
        assertEquals(-32602, errorCode(resp));
    }

    // ---------- 协议边缘 / 错误码 ----------

    @Test
    void unknownMethodIsMethodNotFound() {
        JsonNode resp = session("garbage/call", "{}");
        assertEquals(-32601, errorCode(resp));
    }

    @Test
    void notificationReturnsNull() {
        String req = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        assertEquals(null, server.handle(req));
    }

    @Test
    void unknownNotificationIsSilentlyIgnored() {
        String req = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}";
        assertEquals(null, server.handle(req));
    }

    @Test
    void malformedJsonIsParseError() {
        JsonNode resp = parse(server.handle("{not json"));
        assertEquals(-32700, errorCode(resp));
    }

    @Test
    void batchIsInvalidRequest() {
        String req = "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]";
        JsonNode resp = parse(server.handle(req));
        assertEquals(-32600, errorCode(resp));
    }
}
