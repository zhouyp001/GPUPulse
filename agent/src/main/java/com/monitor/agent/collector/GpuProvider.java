package com.monitor.agent.collector;

import com.monitor.model.GpuInfo;

import java.util.List;

/** GPU 采集器 SPI：返回本机所有 GPU 卡的实时指标。 */
public interface GpuProvider {
    String name();

    List<GpuInfo> collect() throws Exception;
}
