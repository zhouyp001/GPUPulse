package com.monitor.agent;

import com.monitor.agent.collector.GpuProvider;
import com.monitor.agent.collector.JvmMetricsProvider;
import com.monitor.agent.collector.MetricsProvider;
import com.monitor.agent.collector.NvidiaSmiGpuCollector;
import com.monitor.agent.collector.SimGpuCollector;
import com.monitor.model.MetricFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Agent 入口：按固定周期采集指标生成最新帧；上报由独立线程负责（含断线退避重连）。
 * 新增监控维度 = 新增一个 Collector 并在这里注册，其余流程不动。
 * 采集与上报解耦：网络中断不影响采集，恢复后立即用最新帧重连。
 * 采集线程为非守护线程，保持 JVM 常驻运行。
 */
public class AgentMain {
    private static final Logger LOG = Logger.getLogger(AgentMain.class.getName());

    public static void main(String[] args) {
        AgentConfig cfg = new AgentConfig();

        // GPU 采集：模拟（开发/演示）或真实 nvidia-smi
        List<GpuProvider> gpuProviders = new ArrayList<>();
        gpuProviders.add(cfg.simulate() ? new SimGpuCollector() : new NvidiaSmiGpuCollector());

        // 自定义指标采集（可继续追加实现）
        List<MetricsProvider> metricsProviders = new ArrayList<>();
        metricsProviders.add(new JvmMetricsProvider());

        String serverId = cfg.serverId();
        int intervalMs = cfg.intervalMs();

        // 采集线程只负责刷新最新帧；上报线程（Reporter）独立拉取并负责断线退避重试
        AtomicReference<MetricFrame> latestFrame = new AtomicReference<>();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-collect");
            t.setDaemon(false); // 非守护线程，保证 JVM 常驻
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            MetricFrame frame = new MetricFrame(serverId, System.currentTimeMillis());
            for (GpuProvider p : gpuProviders) {
                try {
                    frame.gpus.addAll(p.collect());
                } catch (Exception e) {
                    LOG.warning("GPU 采集失败: " + e);
                }
            }
            for (MetricsProvider p : metricsProviders) {
                try {
                    frame.metrics.putAll(p.collect());
                } catch (Exception e) {
                    LOG.warning("自定义指标采集失败: " + e);
                }
            }
            latestFrame.set(frame);
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        Reporter reporter = new Reporter(cfg, latestFrame::get);
        reporter.start();

        LOG.info("Agent 启动 serverId=" + serverId + " 采集频率=" + intervalMs
                + "ms url=" + cfg.reportUrl() + " simulate=" + cfg.simulate()
                + " 重试退避=" + cfg.retryInitialMs() + "->" + cfg.retryMaxMs() + "ms");
    }
}
