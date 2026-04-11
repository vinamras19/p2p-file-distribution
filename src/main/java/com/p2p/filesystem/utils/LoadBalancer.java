package com.p2p.filesystem.utils;

import com.p2p.filesystem.core.PeerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class LoadBalancer {
    private static final Logger logger = LoggerFactory.getLogger(LoadBalancer.class);

    private static final double WEIGHT_LOAD = 1.5;
    private static final double WEIGHT_LATENCY = 2.0;
    private static final double WEIGHT_ERROR_RATE = 100.0;
    private static final long LATENCY_PENALTY_THRESHOLD_MS = 5000;

    private final Map<String, PeerStats> peerStats = new ConcurrentHashMap<>();
    private final AtomicLong rrCounter = new AtomicLong(0);
    private volatile Strategy strategy = Strategy.ADAPTIVE;

    public enum Strategy { ROUND_ROBIN, LEAST_LOAD, ADAPTIVE }

    public PeerInfo selectPeer(List<PeerInfo> peers) {
        if (peers == null || peers.isEmpty()) return null;

        if (peers.size() == 1) return peers.get(0);

        List<PeerInfo> candidates = peers.stream()
                .filter(this::isViable)
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            logger.debug("No healthy peers found, falling back to full list");
            candidates = peers;
        }

        switch (strategy) {
            case ROUND_ROBIN: return selectRoundRobin(candidates);
            case LEAST_LOAD: return selectLeastLoad(candidates);
            case ADAPTIVE: return selectAdaptive(candidates);
            default: return selectRoundRobin(candidates);
        }
    }

    private PeerInfo selectRoundRobin(List<PeerInfo> peers) {
        int idx = (int) (rrCounter.getAndIncrement() % peers.size());
        return peers.get(Math.abs(idx));
    }

    private PeerInfo selectLeastLoad(List<PeerInfo> peers) {
        return peers.stream()
                .min(Comparator.comparingLong(p -> getStats(p.getPeerId()).activeRequests.get()))
                .orElse(peers.get(0));
    }

    private PeerInfo selectAdaptive(List<PeerInfo> peers) {
        return peers.stream()
                .min(Comparator.comparingDouble(this::calculateCost))
                .orElse(peers.get(0));
    }

    private double calculateCost(PeerInfo peer) {
        PeerStats stats = getStats(peer.getPeerId());

        double loadCost = stats.activeRequests.get() * WEIGHT_LOAD;
        double latencyCost = (stats.avgLatency.get() / 100.0) * WEIGHT_LATENCY;
        double errorCost = stats.errorCount.get() * WEIGHT_ERROR_RATE;

        long staleness = Math.max(0, System.currentTimeMillis() - peer.getLastSeen()) / 1000;

        return loadCost + latencyCost + errorCost + staleness;
    }

    private boolean isViable(PeerInfo p) {
        if (!p.isAlive()) return false;
        PeerStats stats = getStats(p.getPeerId());

        // Circuit breaker
        if (stats.errorCount.get() > 5) return false;

        if (stats.avgLatency.get() > LATENCY_PENALTY_THRESHOLD_MS) return false;

        return true;
    }

    public void recordRequest(String peerId) {
        getStats(peerId).activeRequests.incrementAndGet();
    }

    public void recordResponse(String peerId, long latencyMs, boolean success) {
        PeerStats stats = getStats(peerId);
        stats.activeRequests.decrementAndGet();

        // EMA for latency
        long oldLat = stats.avgLatency.get();
        long newLat = (long) (oldLat * 0.7 + latencyMs * 0.3);
        stats.avgLatency.set(newLat);

        if (success) {
            if (stats.errorCount.get() > 0) stats.errorCount.decrementAndGet();
        } else {
            stats.errorCount.incrementAndGet();
        }
    }

    private PeerStats getStats(String peerId) {
        return peerStats.computeIfAbsent(peerId, k -> new PeerStats());
    }

    private static class PeerStats {
        final AtomicLong activeRequests = new AtomicLong(0);
        final AtomicLong avgLatency = new AtomicLong(100); // Start optimistic
        final AtomicLong errorCount = new AtomicLong(0);
    }
}