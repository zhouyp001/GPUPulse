package com.monitor.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次采集的完整指标帧，是 Agent -&gt; 服务器 -&gt; 浏览器 全链路共用的唯一契约。
 * <ul>
 *   <li>server / timestamp：标识来源服务器与采集时刻</li>
 *   <li>gpus：GPU 卡片列表（支持单机多卡）</li>
 *   <li>metrics：自定义指标字典（name -&gt; value）</li>
 * </ul>
 */
public class MetricFrame {
    /** 被监控服务器标识（与 agent.server.id 一致） */
    public String server;
    public long timestamp;
    public List<GpuInfo> gpus = new ArrayList<>();
    public Map<String, Double> metrics = new LinkedHashMap<>();

    public MetricFrame() {
    }

    public MetricFrame(String server, long timestamp) {
        this.server = server;
        this.timestamp = timestamp;
    }
}
