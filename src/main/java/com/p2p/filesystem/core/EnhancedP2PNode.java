package com.p2p.filesystem.core;

import com.p2p.filesystem.config.P2PConfiguration;
import com.p2p.filesystem.storage.FileIntegrityVerifier;
import com.p2p.filesystem.utils.BackpressureController;
import com.p2p.filesystem.utils.LoadBalancer;
import com.p2p.filesystem.utils.PerformanceMonitor;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class EnhancedP2PNode extends P2PNode {
    private static final Logger logger = LoggerFactory.getLogger(EnhancedP2PNode.class);

    private final PerformanceMonitor monitor;
    private final LoadBalancer loadBalancer;
    private final BackpressureController backpressure;
    private final FileIntegrityVerifier integrityVerifier;

    public EnhancedP2PNode(P2PConfiguration config) {
        super(config);
        this.monitor = new PerformanceMonitor();
        this.loadBalancer = new LoadBalancer();
        this.backpressure = new BackpressureController(50);
        this.integrityVerifier = new FileIntegrityVerifier();
    }

    @Override
    public void start() throws Exception {
        super.start();
        logger.info("Enhanced P2P Node started.");
    }

    @Override
    public CompletableFuture<Boolean> downloadFile(String fileId, Path outputPath)
    {
        if (!backpressure.tryAcquire())
        {
            logger.warn("Node saturated, rejecting download request for {}", fileId);
            return CompletableFuture.completedFuture(false);
        }

        return super.downloadFile(fileId, outputPath)
                .whenComplete((result, ex) -> backpressure.release());
    }

    @Override
    public PeerInfo selectPeer(List<PeerInfo> peers) {
        return loadBalancer.selectPeer(peers);
    }

    @Override
    public void recordPeerRequest(String peerId) {
        loadBalancer.recordRequest(peerId);
    }

    @Override
    public void recordPeerResponse(String peerId, long latencyMs, boolean success) {
        loadBalancer.recordResponse(peerId, latencyMs, success);
    }

    @Override
    public void recordUpload(long bytes) {
        monitor.recordBytes(bytes, false);
    }

    @Override
    public void handleMessage(com.p2p.filesystem.network.P2PMessage msg, Channel channel, int rawBytes) {
        long start = System.nanoTime();
        boolean success = true;

        try {
            monitor.recordBytes(rawBytes, true);
            super.handleMessage(msg, channel, rawBytes);
        } catch (Exception e) {
            success = false;
            throw e;
        } finally {
            long duration = (System.nanoTime() - start) / 1_000_000;
            monitor.recordOp(msg.getType().toString(), duration, success);
        }
    }

    public void optimizePerformance() {
        monitor.reset();
        backpressure.reset();
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return monitor;
    }

    public LoadBalancer getLoadBalancer() {
        return loadBalancer;
    }

    public FileIntegrityVerifier getIntegrityVerifier() {
        return integrityVerifier;
    }

    public Map<String, Object> performHealthCheck() {
        var stats = monitor.getSnapshot();
        boolean healthy = !backpressure.isSaturated();

        return Map.of(
                "isOverallHealthy", healthy,
                "nodeRunning", isRunning(),
                "activeConnections", stats.activeConnections,
                "storageHealthy", getChunkStorage() != null,
                "networkHealthy", stats.failedConnections < 100,
                "backpressureHealthy", healthy,
                "clusterHealthy", getPeers().size() > 0
        );
    }
}