package com.p2p.filesystem.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public class P2PConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(P2PConfiguration.class);

    private String nodeHost = "localhost";
    private int nodePort = 8080;
    private int discoveryPort = 8888;
    private boolean enablePeerDiscovery = true;

    private String storageBasePath = "./storage";
    private int chunkSize = 256 * 1024;

    private String redisHost = "localhost";
    private int redisPort = 6379;
    private String redisPassword = "";
    private int redisPoolSize = 16;

    private boolean enableEncryption = true;
    private boolean verifyPeerCertificates = false;
    private String encryptionAlgorithm = "AES/GCM/NoPadding";

    public P2PConfiguration() {
        loadFromFile("src/main/resources/application.properties");
    }

    public void loadFromFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            logger.debug("Config file not found at {}, using defaults.", path);
            return;
        }

        try (InputStream input = Files.newInputStream(path)) {
            Properties prop = new Properties();
            prop.load(input);

            this.nodeHost = prop.getProperty("node.host", nodeHost);
            this.nodePort = getInt(prop, "node.port", nodePort);
            this.discoveryPort = getInt(prop, "discovery.port", discoveryPort);
            this.enablePeerDiscovery = getBool(prop, "discovery.enabled", enablePeerDiscovery);

            this.storageBasePath = prop.getProperty("storage.path", storageBasePath);
            this.chunkSize = getInt(prop, "storage.chunkSize", chunkSize);

            this.redisHost = prop.getProperty("redis.host", redisHost);
            this.redisPort = getInt(prop, "redis.port", redisPort);
            this.redisPassword = prop.getProperty("redis.password", redisPassword);

            this.enableEncryption = getBool(prop, "security.encryption.enabled", enableEncryption);
            this.verifyPeerCertificates = getBool(prop, "security.verify.peers", verifyPeerCertificates);

            logger.info("Configuration loaded from {}", filePath);
        } catch (IOException e) {
            logger.warn("Failed to load config file: {}", e.getMessage());
        }
    }

    private int getInt(Properties p, String key, int defaultValue) {
        String val = p.getProperty(key);
        try {
            return val != null ? Integer.parseInt(val) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBool(Properties p, String key, boolean defaultValue) {
        String val = p.getProperty(key);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    public String getNodeHost() { return nodeHost; }
    public void setNodeHost(String nodeHost) { this.nodeHost = nodeHost; }

    public int getNodePort() { return nodePort; }
    public void setNodePort(int nodePort) { this.nodePort = nodePort; }

    public int getDiscoveryPort() { return discoveryPort; }

    public boolean isEnablePeerDiscovery() { return enablePeerDiscovery; }

    public String getStorageBasePath() { return storageBasePath; }
    public void setStorageBasePath(String storageBasePath) { this.storageBasePath = storageBasePath; }

    public int getChunkSize() { return chunkSize; }

    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }

    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }

    public String getRedisPassword() { return redisPassword; }

    public int getRedisConnectionPoolSize() { return redisPoolSize; }

    public boolean isEnableEncryption() { return enableEncryption; }
    public boolean isVerifyPeerCertificates() { return verifyPeerCertificates; }
    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
}