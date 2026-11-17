package com.monitor.model;

/** 单张 GPU 卡的实时指标，字段与 nvidia-smi --query-gpu 查询列一一对应。 */
public class GpuInfo {
    /** GPU 序号（0 起） */
    public int index;
    /** 显卡型号名 */
    public String name;
    /** 利用率 % */
    public double utilization;
    /** 温度 °C */
    public double temperature;
    /** 显存已用 MB */
    public double memoryUsed;
    /** 显存总量 MB */
    public double memoryTotal;
    /** 功耗 W */
    public double powerDraw;

    public GpuInfo() {
    }

    public GpuInfo(int index, String name, double utilization, double temperature,
                   double memoryUsed, double memoryTotal, double powerDraw) {
        this.index = index;
        this.name = name;
        this.utilization = utilization;
        this.temperature = temperature;
        this.memoryUsed = memoryUsed;
        this.memoryTotal = memoryTotal;
        this.powerDraw = powerDraw;
    }
}
