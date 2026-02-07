package com.p2p.filesystem.core;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PeerInfo {
    private final String peerId;
    private final String host;
    private final int port;

    private volatile boolean isAlive;
    private volatile long lastSeen;

    private final Set<String> availableFiles = ConcurrentHashMap.newKeySet();

    public PeerInfo(String peerId, String host, int port) {
        this.peerId = peerId;
        this.host = host;
        this.port = port;
        this.isAlive = true;
        this.lastSeen = System.currentTimeMillis();
    }

    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
        this.isAlive = true;
    }

    public void addAvailableFile(String fileId) {
        availableFiles.add(fileId);
    }

    public boolean hasFile(String fileId) {
        return availableFiles.contains(fileId);
    }


    public String getPeerId() { return peerId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isAlive() { return isAlive; }
    public void setAlive(boolean alive) { isAlive = alive; }
    public long getLastSeen() { return lastSeen; }
    public Set<String> getAvailableFiles() { return Collections.unmodifiableSet(availableFiles); }

    @Override
    public String toString() {
        return String.format("%s@%s:%d", peerId, host, port);
    }
}