package com.monitor.agent.collector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义指标示例：JVM 堆内存使用（MB）。
 * 新增自定义指标只需实现 MetricsProvider 并在 AgentMain 注册即可，不改动数据链路。
 */
public class JvmMetricsProvider implements MetricsProvider {
    @Override
    public String name() {
        return "jvm";
    }

    @Override
    public Map<String, Double> collect() {
        Runtime rt = Runtime.getRuntime();
        double usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("jvm.heap_used_mb", usedMb);
        return m;
    }
}
