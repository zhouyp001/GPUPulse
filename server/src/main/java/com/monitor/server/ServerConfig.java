package com.monitor.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** 中央服务器配置：读取内置 server.properties，支持 -Dkey=value 覆盖。 */
public class ServerConfig {
    private final Properties props = new Properties();

    public ServerConfig() {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/server.properties"),
                "找不到内置 server.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载 server.properties 失败", e);
        }
    }

    private String get(String key, String def) {
        return System.getProperty(key, props.getProperty(key, def));
    }

    private int getInt(String key, int def) {
        return Integer.parseInt(get(key, Integer.toString(def)));
    }

    public int port()          { return getInt("server.port", 8080); }
    public String webDir()     { return get("server.web.dir", "web"); }
    public long offlineMs()    { return getInt("server.offline.ms", 10000); }
    public long syncIntervalMs(){ return getInt("server.sync.interval.ms", 3000); }

    /** MCP 端点开关：false 时整个 MCP 面不加载，进程行为与未接入时一致。 */
    public boolean mcpEnabled() { return Boolean.parseBoolean(get("server.mcp.enabled", "true")); }
    /** MCP 端点路径（去掉查询串后的精确匹配）。 */
    public String mcpPath()     { return get("server.mcp.path", "/mcp"); }
    /** MCP 可选共享 token：非空时要求请求携带 Authorization: Bearer <token>；留空=开放。 */
    public String mcpToken()    { return get("server.mcp.token", ""); }
}
