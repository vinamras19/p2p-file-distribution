package com.p2p.filesystem.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

public class BackpressureController {
    private static final Logger logger = LoggerFactory.getLogger(BackpressureController.class);

    private final int maxConcurrentRequests;
    private final Semaphore semaphore;
    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong totalRequests = new AtomicLong(0);

    public BackpressureController(int maxConcurrentRequests) {
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.semaphore = new Semaphore(maxConcurrentRequests, true);
    }

    public boolean tryAcquire() {
        totalRequests.incrementAndGet();

        boolean acquired = semaphore.tryAcquire();
        if (!acquired) {
            rejectedCount.incrementAndGet();
            if (rejectedCount.get() % 100 == 0) {
                logger.warn("High Load: Rejected {} requests. Capacity: {}/{}",
                        rejectedCount.get(), semaphore.availablePermits(), maxConcurrentRequests);
            }
        }
        return acquired;
    }

    public void release() {
        semaphore.release();
    }

    public double getUtilization() {
        int available = semaphore.availablePermits();
        return 1.0 - ((double) available / maxConcurrentRequests);
    }

    public boolean isSaturated() {
        return semaphore.availablePermits() == 0;
    }

    public BackpressureStats getStats() {
        return new BackpressureStats(
                maxConcurrentRequests,
                maxConcurrentRequests - semaphore.availablePermits(),
                rejectedCount.get(),
                totalRequests.get()
        );
    }

    public void reset() {
        rejectedCount.set(0);
        totalRequests.set(0);
    }

    public static class BackpressureStats {
        public final int capacity;
        public final int active;
        public final long rejected;
        public final long total;

        public BackpressureStats(int capacity, int active, long rejected, long total) {
            this.capacity = capacity;
            this.active = active;
            this.rejected = rejected;
            this.total = total;
        }

        @Override
        public String toString() {
            double rejectionRate = total > 0 ? (double) rejected / total * 100 : 0;
            return String.format("Load: %d/%d | Rejected: %d (%.2f%%)",
                    active, capacity, rejected, rejectionRate);
        }
    }
}