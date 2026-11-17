package com.monitor.agent.collector;

import java.util.Map;

/**
 * 自定义指标采集器 SPI：返回 name -&gt; value 字典，合并进指标帧的 metrics 字段。
 * 新增监控维度 = 实现本接口并在 AgentMain 注册，无需改动数据链路。
 */
public interface MetricsProvider {
    String name();

    Map<String, Double> collect() throws Exception;
}
