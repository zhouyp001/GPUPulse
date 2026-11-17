package com.monitor.agent.collector;

import com.monitor.model.GpuInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 无真实 GPU 时的模拟采集器（agent.simulate=true），用于开发调试与面板演示。
 * 数据按正弦波缓慢摆动，模拟实时变化。
 */
public class SimGpuCollector implements GpuProvider {
    private static final String[] NAMES = {"RTX 4090 (模拟)", "RTX A6000 (模拟)"};

    @Override
    public String name() {
        return "gpu-sim";
    }

    @Override
    public List<GpuInfo> collect() {
        double t = System.currentTimeMillis() / 1000.0;
        List<GpuInfo> list = new ArrayList<>();
        for (int i = 0; i < NAMES.length; i++) {
            list.add(new GpuInfo(
                    i,
                    NAMES[i],
                    clip(40 + 45 * Math.sin(t / 6 + i * 2.1)),
                    clip(52 + 15 * Math.sin(t / 9 + i * 1.3)),
                    clip(4000 + 3000 * Math.sin(t / 7 + i)),
                    16376,
                    clip(60 + 90 * Math.abs(Math.sin(t / 5 + i * 1.7)))));
        }
        return list;
    }

    private static double clip(double v) {
        return Math.max(0, v);
    }
}
