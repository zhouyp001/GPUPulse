package com.monitor.agent;

import com.monitor.model.MetricFrame;
import com.monitor.util.Json;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 上报客户端：独立线程 + 指数退避重连。
 * 网络中断或服务端不可达时，不再按固定周期高频轰炸，而是按 initial-&gt;max 指数退避重试；
 * 一旦恢复，立即用最新帧重连并回到正常上报频率。采集与上报解耦，采集频率不受网络影响。
 * <p>HTTP 使用 JDK8 自带的 HttpURLConnection（兼容 Java 8 及更高版本，无第三方依赖）。
 */
public class Reporter {
    private static final Logger LOG = Logger.getLogger(Reporter.class.getName());

    private final URL reportUrl;
    private final int requestTimeoutMs;
    private final Supplier<MetricFrame> frameSource;
    private final long intervalMs;
    private final long retryInitialMs;
    private final long retryMaxMs;
    private final double retryMultiplier;

    private volatile boolean running = true;
    private volatile int consecutiveFailures;

    public Reporter(AgentConfig cfg, Supplier<MetricFrame> frameSource) {
        try {
            this.reportUrl = new URL(cfg.reportUrl());
        } catch (Exception e) {
            throw new IllegalStateException("上报地址非法: " + cfg.reportUrl(), e);
        }
        this.requestTimeoutMs = (int) cfg.requestTimeoutMs();
        this.frameSource = frameSource;
        this.intervalMs = cfg.intervalMs();
        this.retryInitialMs = cfg.retryInitialMs();
        this.retryMaxMs = cfg.retryMaxMs();
        this.retryMultiplier = cfg.retryMultiplier();
    }

    /** 启动独立上报线程（非守护，保持 JVM 常驻）。 */
    public Thread start() {
        Thread t = new Thread(this::run, "agent-reporter");
        t.setDaemon(false);
        t.start();
        return t;
    }

    private void run() {
        while (running) {
            MetricFrame frame = frameSource.get();
            if (frame == null) {
                sleep(intervalMs); // 尚无首帧，稍候再取，不计失败
                continue;
            }
            boolean ok = report(frame);
            if (ok) {
                consecutiveFailures = 0;
                sleep(intervalMs);
            } else {
                consecutiveFailures++;
                long backoff = backoffDelay(consecutiveFailures);
                LOG.log(Level.WARNING, "上报失败（连续 {0} 次），{1}ms 后重试",
                        new Object[]{consecutiveFailures, backoff});
                sleep(backoff);
            }
        }
    }

    /** 上报一帧；成功返回 true，失败返回 false（不抛异常）。 */
    private boolean report(MetricFrame frame) {
        HttpURLConnection conn = null;
        try {
            byte[] body = Json.toJson(frame).getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) reportUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(requestTimeoutMs);
            conn.setReadTimeout(requestTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            if (code >= 400) {
                LOG.log(Level.WARNING, "上报被拒 status={0} body={1}",
                        new Object[]{code, readBody(conn.getErrorStream())});
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.FINE, "上报异常（网络/服务端不可达）：{0}", e.toString());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 读取响应体（最多读 512 字节，用于错误日志）。 */
    private static String readBody(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[128];
            int n;
            while (sb.length() < 512 && (n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 指数退避：initial * multiplier^(n-1)，封顶 max，避免对不可达服务器高频轰炸。 */
    private long backoffDelay(int failures) {
        double delay = retryInitialMs * Math.pow(retryMultiplier, failures - 1);
        if (delay > retryMaxMs) delay = retryMaxMs;
        return (long) delay;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 停止上报线程（供优雅停机使用）。 */
    public void stop() {
        running = false;
    }
}
