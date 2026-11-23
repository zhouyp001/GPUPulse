package com.monitor.server.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.util.Json;

/**
 * MCP 协议核心：JSON-RPC 2.0 方法分发。
 * <p>输入一条请求/通知 JSON 文本，输出对应 JSON-RPC 响应文本；通知返回 {@code null}（HTTP 层回 202 空体）。
 * 无状态、无会话：initialize 不建立会话，每条消息独立处理。</p>
 */
public class McpServer {
    private final McpCapabilities capabilities;

    public McpServer(McpCapabilities capabilities) {
        this.capabilities = capabilities;
    }

    /** 处理一条入站消息；返回 JSON-RPC 响应文本，通知返回 null。 */
    public String handle(String body) {
        JsonNode msg;
        try {
            msg = Json.MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            return error(null, McpConst.ERROR_PARSE, "parse error: " + e.getOriginalMessage());
        }
        if (msg == null || msg.isNull()) {
            return error(null, McpConst.ERROR_PARSE, "empty request");
        }
        if (msg.isArray()) {
            // 本面向无状态单请求设计，不支持批量。
            JsonNode first = msg.size() > 0 && msg.get(0).isObject() ? msg.get(0) : null;
            return error(first == null ? null : first.get("id"),
                    McpConst.ERROR_INVALID_REQUEST, "batch requests not supported");
        }
        if (!msg.isObject()) {
            return error(null, McpConst.ERROR_INVALID_REQUEST, "invalid request");
        }
        return dispatch((ObjectNode) msg);
    }

    private String dispatch(ObjectNode msg) {
        JsonNode id = msg.get("id");

        // 通知（无 id）：一律无响应，即使方法未知（JSON-RPC 规定通知不回应）。
        if (id == null || id.isNull()) {
            return null;
        }

        String method = msg.path("method").asText("");
        if (method.isEmpty()) {
            return error(id, McpConst.ERROR_INVALID_REQUEST, "missing method");
        }
        ObjectNode params = msg.path("params").isObject()
                ? (ObjectNode) msg.path("params") : Json.MAPPER.createObjectNode();

        try {
            ObjectNode result;
            if ("initialize".equals(method)) {
                result = initialize(params);
            } else if ("ping".equals(method)) {
                result = Json.MAPPER.createObjectNode();
            } else if ("tools/list".equals(method)) {
                result = capabilities.tools();
            } else if ("tools/call".equals(method)) {
                String name = params.path("name").asText("");
                if (name.isEmpty()) {
                    throw new IllegalArgumentException("missing tool name");
                }
                ObjectNode args = params.path("arguments").isObject()
                        ? (ObjectNode) params.path("arguments") : null;
                result = capabilities.callTool(name, args);
            } else if ("resources/list".equals(method)) {
                result = capabilities.resources();
            } else if ("resources/read".equals(method)) {
                result = capabilities.readResource(requireParam(params, "uri"));
            } else if ("prompts/list".equals(method)) {
                result = capabilities.prompts();
            } else if ("prompts/get".equals(method)) {
                String name = requireParam(params, "name");
                ObjectNode args = params.path("arguments").isObject()
                        ? (ObjectNode) params.path("arguments") : null;
                result = capabilities.getPrompt(name, args);
            } else {
                return error(id, McpConst.ERROR_METHOD_NOT_FOUND, "method not found: " + method);
            }
            return ok(id, result);
        } catch (IllegalArgumentException e) {
            return error(id, McpConst.ERROR_INVALID_PARAMS, e.getMessage());
        } catch (Exception e) {
            return error(id, McpConst.ERROR_INTERNAL, "internal error: " + e.getMessage());
        }
    }

    private ObjectNode initialize(ObjectNode params) {
        String clientVersion = params.path("protocolVersion").asText("");
        ObjectNode result = Json.MAPPER.createObjectNode();
        result.put("protocolVersion", supportedVersion(clientVersion));
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        caps.putObject("resources");
        caps.putObject("prompts");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", McpConst.SERVER_NAME);
        info.put("version", McpConst.SERVER_VERSION);
        return result;
    }

    /** 回客户端请求的版本（若支持）；否则回 2025-06-18。 */
    private static String supportedVersion(String clientVersion) {
        if (McpConst.PROTOCOL_VERSION_2024.equals(clientVersion)) {
            return McpConst.PROTOCOL_VERSION_2024;
        }
        return McpConst.PROTOCOL_VERSION_LATEST;
    }

    private String ok(JsonNode id, ObjectNode result) {
        ObjectNode resp = Json.MAPPER.createObjectNode();
        resp.put("jsonrpc", McpConst.JSONRPC);
        resp.set("id", id);
        resp.set("result", result);
        return resp.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode resp = Json.MAPPER.createObjectNode();
        resp.put("jsonrpc", McpConst.JSONRPC);
        if (id == null || id.isNull()) {
            resp.putNull("id");
        } else {
            resp.set("id", id);
        }
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp.toString();
    }

    private static String requireParam(ObjectNode params, String field) {
        String v = params.path(field).asText("");
        if (v.isEmpty()) {
            throw new IllegalArgumentException("missing parameter: " + field);
        }
        return v;
    }
}
