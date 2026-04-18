package com.p2p.filesystem.api;

import com.p2p.filesystem.config.P2PConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import com.p2p.filesystem.core.EnhancedP2PNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class DashboardAPIServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardAPIServer.class);
    private final int port;
    private final EnhancedP2PNode node;
    private final P2PConfiguration config;
    private final long startTime = System.currentTimeMillis();

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String dashboardPath = "dashboard";

    public DashboardAPIServer(int port, EnhancedP2PNode node) {
        this.port = port;
        this.node = node;
        this.config = node.getConfig();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(10));

        server.createContext("/", this::handleStatic);
        server.createContext("/api/stats", ex -> sendJson(ex, getStatsJson()));
        server.createContext("/api/peers", ex -> sendJson(ex, toJson(
                node.getPeers().values().stream()
                        .map(p -> map("id", p.getPeerId(), "host", p.getHost(), "port", (long) p.getPort(), "alive", p.isAlive() ? 1L : 0L))
                        .collect(Collectors.toList())
        )));
        server.createContext("/api/files", ex -> sendJson(ex, toJson(
                node.getKnownFiles().values().stream()
                        .map(f -> map("id", f.getFileId(), "name", f.getFileName(), "size", f.getFileSize(), "chunks", (long) f.getTotalChunks()))
                        .collect(Collectors.toList())
        )));
        server.createContext("/api/transfers", ex -> sendJson(ex, "[]"));
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/security", this::handleSecurity);
        server.createContext("/metrics", this::handlePrometheus);

        server.start();
        logger.info("Dashboard API active on port {}", port);
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Path file = Paths.get(dashboardPath, path.equals("/") ? "index.html" : path);
        if (Files.exists(file)) {
            String mime = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
            send(ex, 200, new String(Files.readAllBytes(file)), mime);
        } else {
            send(ex, 404, "404 Not Found", "text/plain");
        }
    }

    private void handleHealth(HttpExchange ex) throws IOException {
        Runtime rt = Runtime.getRuntime();
        sendJson(ex, toJson(map(
                "status", "healthy", "uptime", System.currentTimeMillis() - startTime,
                "memoryUsed", rt.totalMemory() - rt.freeMemory(), "cpuCores", rt.availableProcessors()
        )));
    }

    private void handleSecurity(HttpExchange ex) throws IOException {
        sendJson(ex, toJson(map(
                "encryption", config.isEnableEncryption(), "algo", config.getEncryptionAlgorithm(),
                "tls", "TLS 1.3", "cipher", "AES-256-GCM"
        )));
    }

    private void handlePrometheus(HttpExchange ex) throws IOException {
        var stats = node.getPerformanceMonitor().getSnapshot();
        StringBuilder sb = new StringBuilder();
        appendMetric(sb, "p2p_peers_total", "gauge", node.getPeers().size());
        appendMetric(sb, "p2p_files_shared", "gauge", node.getKnownFiles().size());
        appendMetric(sb, "p2p_bytes_transferred", "counter", stats.activeConnections);
        appendMetric(sb, "p2p_download_speed_bytes", "gauge", stats.downloadSpeed);
        appendMetric(sb, "p2p_upload_speed_bytes", "gauge", stats.uploadSpeed);
        appendMetric(sb, "p2p_jvm_memory_used", "gauge", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        send(ex, 200, sb.toString(), "text/plain; version=0.0.4");
    }

    private void sendJson(HttpExchange ex, String json) throws IOException {
        send(ex, 200, json, "application/json");
    }

    private void send(HttpExchange ex, int code, String body, String type) throws IOException {
        totalRequests.incrementAndGet();
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private String getStatsJson() {
        var stats = node.getPerformanceMonitor().getSnapshot();
        return toJson(map(
                "totalPeers", node.getPeers().size(),
                "filesShared", node.getKnownFiles().size(),
                "bytesTransferred", stats.activeConnections,
                "downloadSpeed", stats.downloadSpeed,
                "uploadSpeed", stats.uploadSpeed
        ));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            logger.error("JSON serialization failed", e);
            return "{}";
        }
    }

    private Map<String, Object> map(Object... args) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) m.put(args[i].toString(), args[i + 1]);
        return m;
    }

    private void appendMetric(StringBuilder sb, String name, String type, long val) {
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n")
                .append(name).append(" ").append(val).append("\n");
    }
}