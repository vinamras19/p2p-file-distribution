package com.p2p.filesystem.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public class PerformanceMonitor {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimeWindow> timers = new ConcurrentHashMap<>();
    private final long startTime = System.currentTimeMillis();

    public void recordBytes(long bytes, boolean isDownload) {
        getCounter(isDownload ? "bytes_rx" : "bytes_tx").add(bytes);
    }

    public void recordOp(String type, long durationMs, boolean success) {
        getCounter(type + (success ? "_success" : "_fail")).increment();
        if (success) {
            timers.computeIfAbsent(type, k -> new TimeWindow()).record(durationMs);
        }
    }

    public void recordConnection(boolean success) {
        getCounter(success ? "conn_success" : "conn_fail").increment();
    }

    private LongAdder getCounter(String key) {
        return counters.computeIfAbsent(key, k -> new LongAdder());
    }

    public StatsSnapshot getSnapshot() {
        long uptime = Math.max(1, (System.currentTimeMillis() - startTime) / 1000);
        long rx = getCount("bytes_rx");
        long tx = getCount("bytes_tx");

        return new StatsSnapshot(
                rx / uptime,
                tx / uptime,
                getCount("conn_success"),
                getCount("conn_fail"),
                uptime
        );
    }

    private long getCount(String key) {
        return counters.getOrDefault(key, new LongAdder()).sum();
    }

    public void reset() {
        counters.values().forEach(LongAdder::reset);
        timers.clear();
    }

    private static class TimeWindow {
        private long max = 0;

        synchronized void record(long ms) {
            if (ms > max) max = ms;
        }
    }

    public static class StatsSnapshot {
        public final long downloadSpeed;
        public final long uploadSpeed;
        public final long activeConnections;
        public final long failedConnections;
        public final long uptimeSeconds;

        public StatsSnapshot(long dl, long ul, long connOk, long connFail, long uptime) {
            this.downloadSpeed = dl;
            this.uploadSpeed = ul;
            this.activeConnections = connOk;
            this.failedConnections = connFail;
            this.uptimeSeconds = uptime;
        }

        @Override
        public String toString() {
            return String.format("Speed: DL %s/s UL %s/s | Uptime: %ds",
                    formatBytes(downloadSpeed), formatBytes(uploadSpeed), uptimeSeconds);
        }

        private String formatBytes(long v) {
            if (v < 1024) return v + " B";
            int z = (63 - Long.numberOfLeadingZeros(v)) / 10;
            return String.format("%.1f %sB", (double)v / (1L << (z*10)), " KMGTPE".charAt(z));
        }
    }
}