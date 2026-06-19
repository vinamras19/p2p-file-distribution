package com.p2p.filesystem.download;

import com.p2p.filesystem.core.FileChunk;
import com.p2p.filesystem.core.FileMetadata;
import com.p2p.filesystem.core.P2PNode;
import com.p2p.filesystem.core.PeerInfo;
import com.p2p.filesystem.network.ChunkRequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public class DownloadManager {
    private static final Logger logger = LoggerFactory.getLogger(DownloadManager.class);

    private final P2PNode node;
    private final ExecutorService downloadPool = Executors.newFixedThreadPool(8);
    private final Map<String, DownloadTask> activeDownloads = new ConcurrentHashMap<>();

    public DownloadManager(P2PNode node) {
        this.node = node;
    }

    public CompletableFuture<Boolean> downloadFile(String fileId, FileMetadata meta, Path output) {
        if (activeDownloads.containsKey(fileId)) {
            logger.warn("Download already in progress for {}", fileId);
            return CompletableFuture.completedFuture(false);
        }

        DownloadTask task = new DownloadTask(fileId, meta, output);
        activeDownloads.put(fileId, task);

        return CompletableFuture.supplyAsync(() -> executeDownload(task), downloadPool);
    }

    private boolean executeDownload(DownloadTask task) {
        logger.info("Starting download: {} ({} chunks)", task.meta.getFileName(), task.totalChunks);

        try {
            createEmptyFile(task.outputPath, task.meta.getFileSize());

            Set<Integer> missingChunks = Collections.synchronizedSet(new HashSet<>());
            for (int i = 0; i < task.totalChunks; i++) missingChunks.add(i);

            int maxRetries = 5;
            int attempt = 0;

            while (!missingChunks.isEmpty() && attempt < maxRetries) {
                attempt++;
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                List<PeerInfo> peers = node.getPeersWithFile(task.fileId);
                if (peers.isEmpty()) {
                    logger.warn("No peers available for file {}. Waiting...", task.fileId);
                    Thread.sleep(2000);
                    continue;
                }

                List<Integer> batch = new ArrayList<>(missingChunks);

                for (Integer chunkIdx : batch) {
                    PeerInfo peer = node.selectPeer(peers);
                    if (peer == null) break;

                    long start = System.nanoTime();
                    node.recordPeerRequest(peer.getPeerId());

                    futures.add(requestChunk(peer, task, chunkIdx)
                            .thenAccept(success -> {
                                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                                node.recordPeerResponse(peer.getPeerId(), latencyMs, success);
                                if (success) missingChunks.remove(chunkIdx);
                            }));
                }

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    logger.debug("Batch timeout, retrying missing chunks...");
                }
            }

            boolean success = missingChunks.isEmpty();
            if (success) {
                logger.info("Download complete: {}", task.outputPath);
            } else {
                logger.error("Download failed. Missing {} chunks.", missingChunks.size());
                Files.deleteIfExists(task.outputPath);
            }
            return success;

        } catch (Exception e) {
            logger.error("Critical download error", e);
            return false;
        } finally {
            activeDownloads.remove(task.fileId);
        }
    }

    private CompletableFuture<Boolean> requestChunk(PeerInfo peer, DownloadTask task, int index) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        task.pendingRequests.put(index, future);

        node.getNetworkHandler().connectToPeer(peer.getHost(), peer.getPort())
                .thenAccept(ch -> {
                    node.getNetworkHandler().sendMessage(ch, new ChunkRequestMessage(node.getNodeId(), task.fileId, index));
                })
                .exceptionally(e -> {
                    future.complete(false);
                    return null;
                });

        return future;
    }

    public void handleChunkReceived(FileChunk chunk) {
        DownloadTask task = activeDownloads.get(chunk.getFileId());
        if (task == null) return;

        int index = chunk.getChunkIndex();
        CompletableFuture<Boolean> f = task.pendingRequests.remove(index);

        String expected = index < task.meta.getChunkHashes().size()
                ? task.meta.getChunkHashes().get(index)
                : null;
        if (expected == null || !expected.equals(chunk.getSha1Hash())) {
            logger.warn("SHA-1 mismatch on chunk {} of {}; rejecting", index, chunk.getFileId());
            if (f != null) f.complete(false);
            return;
        }

        try {
            writeChunkToFile(task.outputPath, index, chunk.getData());
            if (f != null) f.complete(true);
        } catch (IOException e) {
            logger.error("Write error for chunk {}", index, e);
            if (f != null) f.complete(false);
        }
    }

    private void createEmptyFile(Path path, long size) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(size);
        }
    }

    private void writeChunkToFile(Path path, int index, byte[] data) throws IOException {
        long offset = (long) index * node.getConfig().getChunkSize();
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.seek(offset);
            raf.write(data);
        }
    }

    public void shutdown() {
        downloadPool.shutdownNow();
    }

    private static class DownloadTask {
        final String fileId;
        final FileMetadata meta;
        final Path outputPath;
        final int totalChunks;
        final Map<Integer, CompletableFuture<Boolean>> pendingRequests = new ConcurrentHashMap<>();

        DownloadTask(String fileId, FileMetadata meta, Path outputPath) {
            this.fileId = fileId;
            this.meta = meta;
            this.outputPath = outputPath;
            this.totalChunks = meta.getTotalChunks();
        }
    }
}