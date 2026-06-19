package com.p2p.filesystem.core;

import com.p2p.filesystem.config.P2PConfiguration;
import com.p2p.filesystem.security.SecureChannelHandler;
import com.p2p.filesystem.download.DownloadManager;
import com.p2p.filesystem.network.*;
import com.p2p.filesystem.storage.ChunkStorage;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicLong;

public class P2PNode {
    private static final Logger logger = LoggerFactory.getLogger(P2PNode.class);

    protected final String nodeId;
    protected final P2PConfiguration config;
    protected final ChunkStorage chunkStorage;
    protected final P2PNetworkHandler networkHandler;
    protected final PeerDiscovery peerDiscovery;
    protected final DownloadManager downloadManager;
    protected final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();
    protected final Map<String, FileMetadata> knownFiles = new ConcurrentHashMap<>();
    protected final SecureChannelHandler secureChannel;
    protected final AtomicLong rrCounter = new AtomicLong(0);

    private volatile boolean isRunning = false;

    public P2PNode(P2PConfiguration config) {
        this.config = config;
        this.nodeId = UUID.randomUUID().toString();

        this.chunkStorage = new ChunkStorage(
                config.getRedisHost(), config.getRedisPort(),
                java.nio.file.Paths.get(config.getStorageBasePath()),
                config.getRedisConnectionPoolSize(), config.getRedisPassword()
        );

        this.networkHandler = new P2PNetworkHandler(this, config);
        this.secureChannel = new SecureChannelHandler(config);
        this.peerDiscovery = new PeerDiscovery(this, config);
        this.downloadManager = new DownloadManager(this);
    }

    public void start() throws Exception {
        if (isRunning) return;
        isRunning = true;

        logger.info("Starting P2P Node [{}] on port {}", nodeId, config.getNodePort());
        networkHandler.start();
        secureChannel.initialize();
        if (config.isEnablePeerDiscovery()) {
            peerDiscovery.start();
        }
    }

    public void shutdown() {
        isRunning = false;
        networkHandler.shutdown();
        secureChannel.shutdown();
        peerDiscovery.shutdown();
        downloadManager.shutdown();
        chunkStorage.cleanup();
        logger.info("Node shutdown complete.");
    }

    public String addFile(Path path) throws IOException {
        String fileId = UUID.randomUUID().toString();
        FileChunker chunker = new FileChunker(config.getChunkSize());
        List<FileChunk> chunks = chunker.chunkFile(fileId, path);

        List<String> hashes = chunks.stream().map(FileChunk::getSha1Hash).collect(Collectors.toList());
        FileMetadata meta = new FileMetadata(fileId, path.getFileName().toString(),
                java.nio.file.Files.size(path), config.getChunkSize(), hashes);

        for (FileChunk chunk : chunks) {
            chunkStorage.storeChunk(chunk);
        }
        knownFiles.put(fileId, meta);
        networkHandler.broadcast(new FileAnnounceMessage(nodeId, List.of(fileId)));
        return fileId;
    }

    public CompletableFuture<Boolean> downloadFile(String fileId, Path outputPath) {
        FileMetadata meta = knownFiles.get(fileId);
        if (meta == null) {
            logger.warn("Cannot download unknown file: {}", fileId);
            return CompletableFuture.completedFuture(false);
        }
        return downloadManager.downloadFile(fileId, meta, outputPath);
    }

    public void handleMessage(P2PMessage msg, Channel channel, int rawBytes) {
        if (msg.getType() == P2PMessage.Type.HANDSHAKE) {
            networkHandler.sendMessage(channel, new FileAnnounceMessage(nodeId, new ArrayList<>(knownFiles.keySet())));
        } else if (msg.getType() == P2PMessage.Type.CHUNK_REQUEST) {
            handleChunkRequest((ChunkRequestMessage) msg, channel);
        } else if (msg.getType() == P2PMessage.Type.CHUNK_RESPONSE) {
            downloadManager.handleChunkReceived(((ChunkResponseMessage) msg).getChunk());
        } else if (msg.getType() == P2PMessage.Type.FILE_ANNOUNCE) {
            FileAnnounceMessage announce = (FileAnnounceMessage) msg;
            PeerInfo peer = peers.get(announce.getSenderId());
            if (peer != null) {
                for (String fileId : announce.getFileIds()) {
                    peer.addAvailableFile(fileId);
                }
            }
        }
    }

    private void handleChunkRequest(ChunkRequestMessage msg, Channel channel) {
        try {
            FileChunk chunk = chunkStorage.retrieveChunk(msg.getFileId(), msg.getChunkIndex());
            if (chunk != null) {
                networkHandler.sendMessage(channel, new ChunkResponseMessage(nodeId, chunk));
                recordUpload(chunk.getSize());
            } else {
                networkHandler.sendMessage(channel, new ChunkResponseMessage(nodeId, msg.getFileId(), msg.getChunkIndex(), "Not Found"));
            }
        } catch (Exception e) {
            logger.error("Error retrieving chunk", e);
        }
    }

    public void addPeer(PeerInfo peer) {
        if (!peer.getPeerId().equals(nodeId)) {
            peers.put(peer.getPeerId(), peer);
        }
    }

    public List<PeerInfo> getPeersWithFile(String fileId) {
        List<PeerInfo> known = peers.values().stream()
                .filter(p -> p.hasFile(fileId))
                .collect(Collectors.toList());
        if (known.isEmpty()) {
            return new ArrayList<>(peers.values());
        }
        return known;
    }

    public PeerInfo selectPeer(List<PeerInfo> peers) {
        if (peers == null || peers.isEmpty()) return null;
        int idx = (int) (rrCounter.getAndIncrement() % peers.size());
        return peers.get(Math.abs(idx));
    }

    public void recordUpload(long bytes) {
    }

    public void recordPeerRequest(String peerId) {
    }

    public void recordPeerResponse(String peerId, long latencyMs, boolean success) {
    }

    public String getNodeId() { return nodeId; }
    public P2PConfiguration getConfig() { return config; }
    public ChunkStorage getChunkStorage() { return chunkStorage; }
    public P2PNetworkHandler getNetworkHandler() { return networkHandler; }
    public SecureChannelHandler getSecureChannel() { return secureChannel; }
    public Map<String, PeerInfo> getPeers() { return peers; }
    public Map<String, FileMetadata> getKnownFiles() { return knownFiles; }
    public boolean isRunning() { return isRunning; }
    public String getHost() { return config.getNodeHost(); }
    public int getPort() { return config.getNodePort(); }
}