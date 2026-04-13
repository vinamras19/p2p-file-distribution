package com.p2p.filesystem.storage;

import com.p2p.filesystem.core.FileChunk;
import com.p2p.filesystem.utils.BloomFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ChunkStorage {
    private static final Logger logger = LoggerFactory.getLogger(ChunkStorage.class);

    private static final String PREFIX_META = "chunk:meta:";
    private static final String PREFIX_FILE = "file:chunks:";
    private static final int TTL = 86400;

    private final JedisPool redis;
    private final Path rootDir;
    private final BloomFilter bloomFilter;

    public ChunkStorage(String host, int port, Path storagePath, int poolSize, String password) {
        this.rootDir = storagePath;
        this.bloomFilter = new BloomFilter(50000, 0.01);
        this.redis = initRedis(host, port, poolSize, password);
        initStorage();
    }

    private JedisPool initRedis(String host, int port, int poolSize, String pass) {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(poolSize);
        cfg.setMaxIdle(Math.max(2, poolSize / 4));
        cfg.setBlockWhenExhausted(true);
        int timeout = 2000;
        return (pass != null && !pass.isEmpty())
                ? new JedisPool(cfg, host, port, timeout, pass)
                : new JedisPool(cfg, host, port, timeout);
    }

    private void initStorage() {
        try {
            Files.createDirectories(rootDir);
            for (int i = 0; i < 256; i++) {
                Files.createDirectories(rootDir.resolve(String.format("%02x", i)));
            }
        } catch (IOException e) {
            throw new RuntimeException("Storage init failed", e);
        }
    }

    public void storeChunk(FileChunk chunk) throws IOException {
        String key = getChunkKey(chunk.getFileId(), chunk.getChunkIndex());
        Path path = getPath(key);

        if (!Files.exists(path)) {
            Files.write(path, chunk.getData());
        }

        try (Jedis j = redis.getResource()) {
            Pipeline pipe = j.pipelined();
            String metaKey = PREFIX_META + key;
            Map<String, String> meta = Map.of(
                    "hash", chunk.getSha1Hash(),
                    "size", String.valueOf(chunk.getSize()),
                    "path", path.toString()
            );
            pipe.hset(metaKey, meta);
            pipe.expire(metaKey, TTL);
            pipe.sadd(PREFIX_FILE + chunk.getFileId(), String.valueOf(chunk.getChunkIndex()));
            pipe.expire(PREFIX_FILE + chunk.getFileId(), TTL);
            pipe.sync();
        }
        bloomFilter.add(key);
    }

    public FileChunk retrieveChunk(String fileId, int index) throws IOException {
        String key = getChunkKey(fileId, index);
        if (!bloomFilter.mightContain(key)) return null;

        try (Jedis j = redis.getResource()) {
            String pathStr = j.hget(PREFIX_META + key, "path");
            if (pathStr != null) {
                return loadFromDisk(Paths.get(pathStr), fileId, index);
            }
        }
        Path path = getPath(key);
        if (Files.exists(path)) {
            return loadFromDisk(path, fileId, index);
        }
        return null;
    }

    private FileChunk loadFromDisk(Path path, String fileId, int index) throws IOException {
        if (!Files.exists(path)) return null;
        return new FileChunk(fileId, index, Files.readAllBytes(path));
    }

    public List<Integer> getExistingChunks(String fileId) {
        try (Jedis j = redis.getResource()) {
            Set<String> members = j.smembers(PREFIX_FILE + fileId);
            return members.stream().map(Integer::parseInt).sorted().collect(Collectors.toList());
        }
    }

    public StorageStats getStats() {
        long totalFiles = 0;
        long totalBytes = 0;
        try (Stream<Path> walk = Files.walk(rootDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            totalFiles = files.size();
            for (Path p : files) totalBytes += Files.size(p);
        } catch (Exception e) {
            logger.warn("Failed to calculate storage stats", e);
        }
        return new StorageStats(totalFiles, totalBytes);
    }

    private String getChunkKey(String fileId, int index) {
        return fileId + "_" + index;
    }

    private Path getPath(String chunkKey) {
        String hash = Integer.toHexString(chunkKey.hashCode());
        String subdir = String.format("%02x", (hash.hashCode() & 0xFF));
        return rootDir.resolve(subdir).resolve(chunkKey + ".chunk");
    }

    public void cleanup() {
        if (redis != null) redis.close();
    }

    public static class StorageStats {
        private final long totalChunks;
        private final long totalBytes;

        public StorageStats(long chunks, long bytes) {
            this.totalChunks = chunks;
            this.totalBytes = bytes;
        }

        @Override
        public String toString() {
            return String.format("%d chunks (%d bytes)", totalChunks, totalBytes);
        }

        public long getTotalChunks() { return totalChunks; }
        public long getTotalBytes() { return totalBytes; }
    }
}