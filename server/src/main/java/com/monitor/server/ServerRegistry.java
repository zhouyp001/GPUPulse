package com.monitor.server;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.model.MetricFrame;
import com.monitor.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存注册表：每个 server 只保留最新指标帧与最近上报时间，据此判定在线/离线。
 * 按项目契约不持久化——本系统只做实时展示。
 */
public class ServerRegistry {
    private static final class Entry {
        volatile boolean online;
        volatile long lastSeen;
        volatile MetricFrame frame;
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Agent 上报一帧：更新最新帧、时间戳并标记在线。 */
    public void update(MetricFrame frame) {
        Entry e = entries.compute(frame.server, (k, old) -> old == null ? new Entry() : old);
        e.frame = frame;
        e.lastSeen = System.currentTimeMillis();
        e.online = true;
    }

    /** 找出超时未上报的服务器并标记离线；返回状态发生变化的 serverId。 */
    public List<String> markOffline(long now, long thresholdMs) {
        List<String> changed = new ArrayList<>();
        entries.forEach((id, e) -> {
            if (e.online && now - e.lastSeen > thresholdMs) {
                e.online = false;
                changed.add(id);
            }
        });
        return changed;
    }

    /** 清空所有离线（未连接）记录，返回被移除的 serverId 列表。在线记录保留。 */
    public List<String> removeOffline() {
        List<String> removed = new ArrayList<>();
        entries.forEach((id, e) -> {
            if (!e.online) {
                entries.remove(id);
                removed.add(id);
            }
        });
        return removed;
    }

    /** 全量快照：面板 WebSocket 连接建立后一次性渲染所有服务器。 */
    public ObjectNode snapshot() {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "snapshot");
        ObjectNode servers = root.putObject("servers");
        entries.forEach((id, e) -> servers.set(id, serverNode(id)));
        return root;
    }

    /** 单台服务器实时更新消息。 */
    public ObjectNode updateMessage(String id) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "update");
        n.set("data", serverNode(id));
        return n;
    }

    /** 单台服务器状态变化（离线）消息。 */
    public ObjectNode statusMessage(String id) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("type", "sync");
        n.set("data", serverNode(id));
        return n;
    }

    private ObjectNode serverNode(String id) {
        Entry e = entries.get(id);
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("server", id);
        n.put("online", e != null && e.online);
        n.put("lastSeen", e == null ? 0L : e.lastSeen);
        if (e != null && e.frame != null) {
            n.set("frame", Json.MAPPER.valueToTree(e.frame));
        } else {
            n.putNull("frame");
        }
        return n;
    }
}
