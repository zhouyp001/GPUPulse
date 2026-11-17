package com.monitor.agent;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/** Agent 配置：读取内置 agent.properties，支持 -Dkey=value 覆盖（优先级更高）。 */
public class AgentConfig {
    private final Properties props = new Properties();

    public AgentConfig() {
        try (InputStream in = Objects.requireNonNull(
                getClass().getResourceAsStream("/agent.properties"),
                "找不到内置 agent.properties")) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("加载 agent.properties 失败", e);
        }
    }

    private String get(String key, String def) {
        return System.getProperty(key, props.getProperty(key, def));
    }

    private int getInt(String key, int def) {
        return Integer.parseInt(get(key, Integer.toString(def)));
    }

    public String serverId()   { return get("agent.server.id", "server1"); }
    public String serverHost() { return get("agent.server.host", "127.0.0.1"); }
    public int serverPort()    { return getInt("agent.server.port", 8080); }
    public int intervalMs()    { return getInt("agent.interval.ms", 2000); }
    public boolean simulate()  { return Boolean.parseBoolean(get("agent.simulate", "false")); }
    public long requestTimeoutMs() { return getInt("agent.request.timeout.ms", 3000); }
    public long retryInitialMs()   { return getInt("agent.retry.initial.ms", 1000); }
    public long retryMaxMs()       { return getInt("agent.retry.max.ms", 30000); }
    public double retryMultiplier(){ return Double.parseDouble(get("agent.retry.multiplier", "2.0")); }

    public String reportUrl() {
        return "http://" + serverHost() + ":" + serverPort() + "/report";
    }
}
