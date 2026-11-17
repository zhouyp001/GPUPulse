package com.monitor.server;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.monitor.model.GpuInfo;
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

    /**
     * GPU 负载列表：对外 API，供三方系统按空闲度挑选服务器（如 LLM 调度路由）。
     * 只读当前内存中的最新帧，不持久化。返回按负载从低到高排序（最空闲在前）的数组。
     *
     * @param all true 时包含离线服务器（标 online:false，负载为 -1 排最后），否则只返回在线。
     */
    public ObjectNode gpuLoad(boolean all) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("type", "gpu-load");
        root.put("timestamp", System.currentTimeMillis());
        ArrayNode servers = root.putArray("servers");

        List<ObjectNode> list = new ArrayList<>();
        entries.forEach((id, e) -> {
            if (!all && (e == null || !e.online)) {
                return;
            }
            list.add(gpuLoadNode(id, e));
        });
        list.sort((a, b) -> Double.compare(a.path("load").asDouble(-1), b.path("load").asDouble(-1)));
        list.forEach(servers::add);
        return root;
    }

    private ObjectNode gpuLoadNode(String id, Entry e) {
        ObjectNode n = Json.MAPPER.createObjectNode();
        n.put("server", id);
        boolean online = e != null && e.online;
        n.put("online", online);
        n.put("lastSeen", e == null ? 0L : e.lastSeen);

        List<GpuInfo> gpus = (e != null && e.frame != null) ? e.frame.gpus : null;
        int count = gpus == null ? 0 : gpus.size();
        n.put("gpuCount", count);

        double utilSum = 0;
        double utilMax = -1;
        double memUsedGb = 0;
        double memTotalGb = 0;
        ArrayNode gpuArr = n.putArray("gpus");
        if (gpus != null) {
            for (GpuInfo g : gpus) {
                ObjectNode gn = Json.MAPPER.createObjectNode();
                gn.put("index", g.index);
                gn.put("name", g.name == null ? "" : g.name);
                gn.put("utilization", round1(g.utilization));
                gn.put("temperature", round1(g.temperature));
                gn.put("memoryUsedMb", g.memoryUsed);
                gn.put("memoryTotalMb", g.memoryTotal);
                gn.put("freeMemoryMb", round1(g.memoryTotal - g.memoryUsed));
                gn.put("powerDraw", round1(g.powerDraw));
                gpuArr.add(gn);

                utilSum += g.utilization;
                utilMax = Math.max(utilMax, g.utilization);
                memUsedGb += g.memoryUsed / 1024.0;
                memTotalGb += g.memoryTotal / 1024.0;
            }
        }

        // load 取最高 GPU 利用率（任何一张卡忙即视为忙），作为空闲度主排序依据。
        double load = count > 0 ? utilMax : -1;
        n.put("load", load < 0 ? -1 : round1(load));
        n.put("utilizationAvg", count > 0 ? round1(utilSum / count) : -1);
        n.put("utilizationMax", load < 0 ? -1 : round1(load));
        n.put("memoryUsedGb", round1(memUsedGb));
        n.put("memoryTotalGb", round1(memTotalGb));
        n.put("freeMemoryGb", round1(memTotalGb - memUsedGb));
        return n;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
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
