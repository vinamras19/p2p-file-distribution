package com.p2p.filesystem.benchmark;

import com.p2p.filesystem.config.P2PConfiguration;
import com.p2p.filesystem.core.*;
import com.p2p.filesystem.storage.ChunkStorage;
import com.p2p.filesystem.storage.FileIntegrityVerifier;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class LoadTestHarness {

    private static final int[] TEST_SIZES_MB = {1, 10, 50, 100};
    private static final int CHUNK_SIZE = 256 * 1024;
    private static final Path TEST_DIR = Paths.get("./loadtest-tmp");

    private static final List<String> results = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("P2P File Distribution System — Load Test\n");

        Files.createDirectories(TEST_DIR);

        try {
            phaseChunking();
            phaseStorage();
            phaseTransfer();
            phaseBackpressure();
            printSummary();
        } finally {
            cleanup();
        }
    }

    private static void phaseChunking() throws IOException {
        System.out.println("-- Phase 1: Chunking Throughput --\n");

        FileChunker chunker = new FileChunker(CHUNK_SIZE);

        for (int sizeMB : TEST_SIZES_MB) {
            Path file = generateTestFile("chunk-test-" + sizeMB + "MB.bin", sizeMB);

            long start = System.nanoTime();
            List<FileChunk> chunks = chunker.chunkFile("test-" + sizeMB, file);
            long elapsed = System.nanoTime() - start;

            double seconds = elapsed / 1_000_000_000.0;
            double mbps = sizeMB / seconds;

            String result = String.format("  %4d MB -> %d chunks in %.3fs  (%.1f MB/s)",
                    sizeMB, chunks.size(), seconds, mbps);
            System.out.println(result);
            results.add("Chunk " + sizeMB + "MB: " + String.format("%.1f MB/s", mbps));
        }
        System.out.println();
    }

    private static void phaseStorage() throws Exception {
        System.out.println("-- Phase 2: Storage Write/Read --\n");

        P2PConfiguration config = createConfig(9099, "./loadtest-tmp/storage-bench");
        ChunkStorage storage = new ChunkStorage(
                config.getRedisHost(), config.getRedisPort(),
                Paths.get(config.getStorageBasePath()),
                config.getRedisConnectionPoolSize(), config.getRedisPassword()
        );

        try {
            int numChunks = 400;
            byte[] data = new byte[CHUNK_SIZE];
            new Random().nextBytes(data);

            long writeStart = System.nanoTime();
            for (int i = 0; i < numChunks; i++) {
                storage.storeChunk(new FileChunk("bench-file", i, data));
            }
            long writeElapsed = System.nanoTime() - writeStart;
            double writeSec = writeElapsed / 1_000_000_000.0;
            double writeMBps = (numChunks * CHUNK_SIZE / 1_048_576.0) / writeSec;

            System.out.printf("  Write: %d chunks (100 MB) in %.3fs  (%.1f MB/s)%n",
                    numChunks, writeSec, writeMBps);
            results.add("Storage Write: " + String.format("%.1f MB/s", writeMBps));

            long readStart = System.nanoTime();
            for (int i = 0; i < numChunks; i++) {
                FileChunk chunk = storage.retrieveChunk("bench-file", i);
                if (chunk == null) {
                    System.err.println("  WARNING: chunk " + i + " not found on read");
                }
            }
            long readElapsed = System.nanoTime() - readStart;
            double readSec = readElapsed / 1_000_000_000.0;
            double readMBps = (numChunks * CHUNK_SIZE / 1_048_576.0) / readSec;

            System.out.printf("  Read:  %d chunks (100 MB) in %.3fs  (%.1f MB/s)%n",
                    numChunks, readSec, readMBps);
            results.add("Storage Read: " + String.format("%.1f MB/s", readMBps));

            long bloomStart = System.nanoTime();
            int bloomChecks = 10_000;
            int filtered = 0;
            for (int i = 0; i < bloomChecks; i++) {
                FileChunk result = storage.retrieveChunk("nonexistent-file", i);
                if (result == null) filtered++;
            }
            long bloomElapsed = System.nanoTime() - bloomStart;
            double bloomUs = (bloomElapsed / 1_000.0) / bloomChecks;

            System.out.printf("  Bloom filter: %d/%d absent lookups short-circuited (%.2f us avg)%n",
                    filtered, bloomChecks, bloomUs);
            results.add("Bloom Filter: " + String.format("%.2f us/lookup", bloomUs));

        } finally {
            storage.cleanup();
        }
        System.out.println();
    }

    private static void phaseTransfer() throws Exception {
        System.out.println("-- Phase 3: End-to-End Transfer --\n");

        P2PConfiguration configA = createConfig(9010, "./loadtest-tmp/node-a");
        P2PConfiguration configB = createConfig(9011, "./loadtest-tmp/node-b");

        EnhancedP2PNode nodeA = new EnhancedP2PNode(configA);
        EnhancedP2PNode nodeB = new EnhancedP2PNode(configB);

        try {
            nodeA.start();
            nodeB.start();
            Thread.sleep(500);

            nodeB.addPeer(new PeerInfo(nodeA.getNodeId(), "localhost", 9010));
            nodeA.addPeer(new PeerInfo(nodeB.getNodeId(), "localhost", 9011));

            for (int sizeMB : new int[]{10, 50, 100}) {
                Path sourceFile = generateTestFile("transfer-" + sizeMB + "MB.bin", sizeMB);
                Path outputFile = TEST_DIR.resolve("received-" + sizeMB + "MB.bin");
                Files.deleteIfExists(outputFile);

                String fileId = nodeA.addFile(sourceFile);
                FileMetadata meta = nodeA.getKnownFiles().get(fileId);

                nodeB.getKnownFiles().put(fileId, meta);

                long start = System.nanoTime();
                CompletableFuture<Boolean> download = nodeB.downloadFile(fileId, outputFile);
                boolean success = download.get(60, TimeUnit.SECONDS);
                long elapsed = System.nanoTime() - start;

                double seconds = elapsed / 1_000_000_000.0;
                double mbps = sizeMB / seconds;

                String integrityStatus = "SKIPPED";
                if (success && Files.exists(outputFile)) {
                    FileIntegrityVerifier verifier = new FileIntegrityVerifier();
                    boolean intact = verifier.verifyFile(outputFile, meta);
                    integrityStatus = intact ? "PASS" : "FAIL";
                }

                String status = success ? "OK" : "FAIL";
                System.out.printf("  %4d MB -> %s in %.3fs  (%.1f MB/s)  integrity: %s%n",
                        sizeMB, status, seconds, mbps, integrityStatus);
                results.add("Transfer " + sizeMB + "MB: " +
                        String.format("%.1f MB/s", mbps) + " [" + integrityStatus + "]");

                Files.deleteIfExists(outputFile);
            }
        } catch (TimeoutException e) {
            System.out.println("  Transfer timed out (60s limit)");
            results.add("Transfer: TIMEOUT");
        } finally {
            nodeA.shutdown();
            nodeB.shutdown();
        }
        System.out.println();
    }

    private static void phaseBackpressure() throws Exception {
        System.out.println("-- Phase 4: Backpressure --\n");

        P2PConfiguration configA = createConfig(9020, "./loadtest-tmp/bp-node-a");
        P2PConfiguration configB = createConfig(9021, "./loadtest-tmp/bp-node-b");

        EnhancedP2PNode nodeA = new EnhancedP2PNode(configA);
        EnhancedP2PNode nodeB = new EnhancedP2PNode(configB);

        try {
            nodeA.start();
            nodeB.start();
            Thread.sleep(500);

            nodeB.addPeer(new PeerInfo(nodeA.getNodeId(), "localhost", 9020));
            nodeA.addPeer(new PeerInfo(nodeB.getNodeId(), "localhost", 9021));

            Path sourceFile = generateTestFile("bp-test.bin", 10);
            String fileId = nodeA.addFile(sourceFile);
            FileMetadata meta = nodeA.getKnownFiles().get(fileId);
            nodeB.getKnownFiles().put(fileId, meta);

            int[] concurrencyLevels = {10, 25, 50, 75, 100};
            ExecutorService pool = Executors.newFixedThreadPool(100);

            for (int concurrency : concurrencyLevels) {
                List<CompletableFuture<Boolean>> futures = new ArrayList<>();

                long start = System.nanoTime();
                for (int i = 0; i < concurrency; i++) {
                    Path out = TEST_DIR.resolve("bp-out-" + i + ".bin");
                    Files.deleteIfExists(out);
                    futures.add(nodeB.downloadFile(fileId, out));
                }

                int accepted = 0, rejected = 0;
                for (CompletableFuture<Boolean> f : futures) {
                    try {
                        boolean result = f.get(30, TimeUnit.SECONDS);
                        if (result) accepted++;
                        else rejected++;
                    } catch (Exception e) {
                        rejected++;
                    }
                }
                long elapsed = System.nanoTime() - start;
                double seconds = elapsed / 1_000_000_000.0;

                System.out.printf("  Concurrency %3d -> accepted: %d  rejected: %d  (%.3fs)%n",
                        concurrency, accepted, rejected, seconds);
                results.add(String.format("Backpressure @%d: %d accepted, %d rejected",
                        concurrency, accepted, rejected));

                Thread.sleep(500);
            }

            pool.shutdown();
        } finally {
            nodeA.shutdown();
            nodeB.shutdown();
        }
        System.out.println();
    }

    private static void printSummary() {
        System.out.println("RESULTS SUMMARY");
        for (String r : results) {
            System.out.println("  " + r);
        }
        System.out.println();
    }

    private static P2PConfiguration createConfig(int port, String storagePath) {
        P2PConfiguration config = new P2PConfiguration();
        config.setNodeHost("localhost");
        config.setNodePort(port);
        config.setStorageBasePath(storagePath);
        config.setRedisHost("localhost");
        config.setRedisPort(6379);
        return config;
    }

    private static Path generateTestFile(String name, int sizeMB) throws IOException {
        Path path = TEST_DIR.resolve(name);
        if (Files.exists(path) && Files.size(path) == sizeMB * 1_048_576L) {
            return path;
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(sizeMB * 1_048_576L);
            byte[] block = new byte[1_048_576];
            Random rng = new Random(42);
            for (int i = 0; i < sizeMB; i++) {
                rng.nextBytes(block);
                raf.write(block);
            }
        }
        return path;
    }

    private static void cleanup() {
        System.out.println("Cleaning up test artifacts...");
        try {
            if (Files.exists(TEST_DIR)) {
                Files.walk(TEST_DIR)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); }
                            catch (IOException ignored) {}
                        });
            }
        } catch (IOException e) {
            System.err.println("Cleanup warning: " + e.getMessage());
        }
    }
}