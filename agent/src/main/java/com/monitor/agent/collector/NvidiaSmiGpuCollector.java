package com.monitor.agent.collector;

import com.monitor.model.GpuInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GPU 采集器：调用 <code>nvidia-smi --query-gpu</code> 的 CSV 输出并解析。
 * 跨平台可移植、零额外依赖（无需 NVML/JNA）。
 */
public class NvidiaSmiGpuCollector implements GpuProvider {
    private static final Logger LOG = Logger.getLogger(NvidiaSmiGpuCollector.class.getName());

    private static final String[] QUERY = {
            "index", "name", "utilization.gpu", "memory.used",
            "memory.total", "temperature.gpu", "power.draw"
    };

    @Override
    public String name() {
        return "gpu";
    }

    @Override
    public List<GpuInfo> collect() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("nvidia-smi");
        cmd.add("--query-gpu=" + String.join(",", QUERY));
        cmd.add("--format=csv,noheader,nounits");

        Process process = new ProcessBuilder(cmd).start();
        process.getOutputStream().close(); // 释放子进程 stdin，避免其阻塞等待
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                List<GpuInfo> gpus = new ArrayList<>();
                String line;
                int forcedIndex = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    GpuInfo gpu = parseLine(line);
                    if (gpu != null) {
                        gpu.index = forcedIndex++; // 用顺序序号，避免 CSV index 空洞
                        gpus.add(gpu);
                    }
                }
                return gpus;
            }
        } finally {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    /** 解析形如 "0, NVIDIA GeForce RTX 4090, 36, 1155, 16376, 56, 8.34" 的一行。 */
    private GpuInfo parseLine(String line) {
        String[] f = line.split(",");
        if (f.length < 2) {
            return null;
        }
        GpuInfo gpu = new GpuInfo();
        gpu.name = f[1].trim();
        gpu.utilization = parseDouble(f, 2);
        gpu.memoryUsed = parseDouble(f, 3);
        gpu.memoryTotal = parseDouble(f, 4);
        gpu.temperature = parseDouble(f, 5);
        gpu.powerDraw = parseDouble(f, 6);
        return gpu;
    }

    /** 容错解析；"[N/A]" 等不可解析值归 0，避免 NaN 破坏 JSON（前端 JSON.parse 会失败）。 */
    private static double parseDouble(String[] f, int idx) {
        if (idx >= f.length) {
            return 0;
        }
        String s = f[idx].trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            LOG.log(Level.FINE, "nvidia-smi 字段不可解析: {0}", s);
            return 0;
        }
    }
}
