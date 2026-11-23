package com.monitor.server.mcp;

/** MCP（Model Context Protocol）常量：协议版本、JSON-RPC 方法与标准错误码。 */
public final class McpConst {
    public static final String JSONRPC = "2.0";

    /** 本项目支持的协议版本：2025-06-18（新）与 2024-11-05（旧），initialize 时按客户端请求回对应版本。 */
    public static final String PROTOCOL_VERSION_LATEST = "2025-06-18";
    public static final String PROTOCOL_VERSION_2024 = "2024-11-05";

    public static final String SERVER_NAME = "server-monitor";
    public static final String SERVER_VERSION = "0.1.0";

    // JSON-RPC 2.0 标准错误码
    public static final int ERROR_PARSE = -32700;
    public static final int ERROR_INVALID_REQUEST = -32600;
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    public static final int ERROR_INVALID_PARAMS = -32602;
    public static final int ERROR_INTERNAL = -32603;

    private McpConst() {
    }
}
