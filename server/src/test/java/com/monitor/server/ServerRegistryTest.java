package com.monitor.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.monitor.model.GpuInfo;
import com.monitor.model.MetricFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ServerRegistry.gpuLoad(...) —— 对外 API，供三方系统按空闲度挑选服务器。 */
class ServerRegistryTest {

    private static MetricFrame frame(String server, GpuInfo... gpus) {
        MetricFrame f = new MetricFrame(server, 0L);
        for (GpuInfo g : gpus) {
            f.gpus.add(g);
        }
        return f;
    }

    /** 把已 update 的服务器强制标为离线（用未来时间戳保证 now-lastSeen > 阈值）。 */
    private static void forceOffline(ServerRegistry reg) {
        reg.markOffline(System.currentTimeMillis() + 1000, 0);
    }

    @Test
    void sortsByLoadAscending_mostIdleFirst() {
        ServerRegistry reg = new ServerRegistry();
        reg.update(frame("busy", new GpuInfo(0, "A", 90, 70, 1000, 8000, 200)));
        reg.update(frame("idle", new GpuInfo(0, "A", 5, 40, 100, 8000, 50)));
        reg.update(frame("mid", new GpuInfo(0, "A", 50, 55, 500, 8000, 120)));

        JsonNode servers = reg.gpuLoad(false).path("servers");
        assertEquals(3, servers.size());
        assertEquals("idle", servers.get(0).path("server").asText());
        assertEquals("mid", servers.get(1).path("server").asText());
        assertEquals("busy", servers.get(2).path("server").asText());
    }

    @Test
    void loadEqualsMaxGpuUtilization() {
        ServerRegistry reg = new ServerRegistry();
        // 两张卡：40% 与 80% -> load 取最高 = 80
        reg.update(frame("multi",
                new GpuInfo(0, "A", 40, 60, 1000, 8000, 150),
                new GpuInfo(1, "B", 80, 65, 2000, 8000, 180)));

        JsonNode node = reg.gpuLoad(false).path("servers").get(0);
        assertEquals(80.0, node.path("load").asDouble(), 0.001);
        assertEquals(2, node.path("gpuCount").asInt());
        assertEquals(2, node.path("gpus").size());
        assertEquals(60.0, node.path("utilizationAvg").asDouble(), 0.001); // (40+80)/2
        assertEquals(80.0, node.path("utilizationMax").asDouble(), 0.001);
        assertTrue(node.path("online").asBoolean());
    }

    @Test
    void memoryReportedInGb() {
        ServerRegistry reg = new ServerRegistry();
        reg.update(frame("mem", new GpuInfo(0, "A", 10, 50, 2048, 8192, 100)));

        JsonNode node = reg.gpuLoad(false).path("servers").get(0);
        // 2048MB = 2GB，8192MB = 8GB
        assertEquals(2.0, node.path("memoryUsedGb").asDouble(), 0.001);
        assertEquals(8.0, node.path("memoryTotalGb").asDouble(), 0.001);
        assertEquals(6.0, node.path("freeMemoryGb").asDouble(), 0.001);
        // 每张卡的明细：freeMemoryMb
        assertEquals(6144.0, node.path("gpus").get(0).path("freeMemoryMb").asDouble(), 0.001);
        assertEquals(2048.0, node.path("gpus").get(0).path("memoryUsedMb").asDouble(), 0.001);
    }

    @Test
    void defaultExcludesOffline_allIncludesThem() {
        ServerRegistry off = new ServerRegistry();
        off.update(frame("gone", new GpuInfo(0, "A", 20, 50, 100, 8000, 50)));
        forceOffline(off);

        // 默认（all=false）：离线服务器不出现
        assertEquals(0, off.gpuLoad(false).path("servers").size());

        // all=true：包含离线，online:false，且其 load 保留最近帧
        JsonNode node = off.gpuLoad(true).path("servers").get(0);
        assertFalse(node.path("online").asBoolean());
        assertEquals(20.0, node.path("load").asDouble(), 0.001);
    }

    @Test
    void noGpu_yieldsLoadMinusOne() {
        ServerRegistry reg = new ServerRegistry();
        reg.update(new MetricFrame("empty", 0L)); // 有帧但无 GPU

        JsonNode node = reg.gpuLoad(false).path("servers").get(0);
        assertEquals(0, node.path("gpuCount").asInt());
        assertEquals(-1.0, node.path("load").asDouble(), 0.001);
        assertEquals(-1.0, node.path("utilizationAvg").asDouble(), 0.001);
        assertEquals(0, node.path("gpus").size());
    }
}
