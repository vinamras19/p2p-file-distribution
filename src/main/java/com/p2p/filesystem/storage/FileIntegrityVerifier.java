package com.p2p.filesystem.storage;

import com.p2p.filesystem.core.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

public class FileIntegrityVerifier {
    private static final Logger logger = LoggerFactory.getLogger(FileIntegrityVerifier.class);

    public boolean verifyFile(Path path, FileMetadata metadata) {
        if (!Files.exists(path)) return false;

        try {
            if (Files.size(path) != metadata.getFileSize()) {
                logger.warn("Size mismatch for {}: expected {}, got {}",
                        path, metadata.getFileSize(), Files.size(path));
                return false;
            }

            String expectedHash = calculateMerkleRoot(metadata.getChunkHashes());

            if (metadata.getMerkleRoot() != null && !expectedHash.equals(metadata.getMerkleRoot())) {
                logger.warn("Merkle root mismatch for {}", path);
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.error("Verification failed for {}", path, e);
            return false;
        }
    }

    public String calculateFileHash(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return hexDigest(is);
        }
    }

    public String calculateMerkleRoot(List<String> chunkHashes) {
        if (chunkHashes == null || chunkHashes.isEmpty()) return "";

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String hash : chunkHashes) {
                md.update(hash.getBytes());
            }
            return toHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("SHA-1 not supported", e);
        }
    }

    private String hexDigest(InputStream is) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            return toHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Hash calculation failed", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}