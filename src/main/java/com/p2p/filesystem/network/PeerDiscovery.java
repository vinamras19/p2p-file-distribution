package com.p2p.filesystem.network;

import com.p2p.filesystem.config.P2PConfiguration;
import com.p2p.filesystem.core.P2PNode;
import com.p2p.filesystem.core.PeerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.InetSocketAddress;

public class PeerDiscovery {
    private static final Logger logger = LoggerFactory.getLogger(PeerDiscovery.class);

    private static final String MULTICAST_GROUP = "224.0.1.200";
    private static final int DISCOVERY_BUFFER = 1024;
    private static final String MSG_DISCOVERY = "P2P_DISCOVERY";
    private static final String MSG_RESPONSE = "P2P_RESPONSE";

    private final P2PNode node;
    private final P2PConfiguration config;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final ExecutorService listenerService = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService broadcasterService = Executors.newSingleThreadScheduledExecutor();

    private MulticastSocket multicastSocket;
    private InetAddress groupAddress;

    public PeerDiscovery(P2PNode node, P2PConfiguration config) {
        this.node = node;
        this.config = config;
    }

    public void start() {
        if (isRunning.getAndSet(true)) return;

        try {
            logger.info("Starting UDP Multicast Discovery on {}:{}", MULTICAST_GROUP, config.getDiscoveryPort());

            groupAddress = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket = new MulticastSocket(config.getDiscoveryPort());
            multicastSocket.joinGroup(new InetSocketAddress(groupAddress, config.getDiscoveryPort()), null);
            multicastSocket.setSoTimeout(0);

            listenerService.submit(this::listenLoop);

            broadcasterService.scheduleAtFixedRate(
                    this::broadcastPresence,
                    0, 5, TimeUnit.SECONDS
            );

        } catch (IOException e) {
            logger.error("Failed to initialize Peer Discovery: {}", e.getMessage());
            shutdown();
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[DISCOVERY_BUFFER];
        while (isRunning.get()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                handleDiscoveryPacket(message, packet.getAddress());

            } catch (IOException e) {
                if (isRunning.get()) logger.warn("Multicast receive error: {}", e.getMessage());
            }
        }
    }

    private void handleDiscoveryPacket(String message, InetAddress senderAddr) {
        String[] parts = message.split("\\|");
        if (parts.length < 4) return;

        String type = parts[0];
        String peerId = parts[1];
        int peerTcpPort = Integer.parseInt(parts[3]);

        if (peerId.equals(node.getNodeId())) return;

        if (MSG_DISCOVERY.equals(type)) {
            node.addPeer(new PeerInfo(peerId, senderAddr.getHostAddress(), peerTcpPort));
            sendResponse();
        } else if (MSG_RESPONSE.equals(type)) {
            node.addPeer(new PeerInfo(peerId, senderAddr.getHostAddress(), peerTcpPort));
        }
    }

    private void broadcastPresence() {
        sendMulticast(String.format("%s|%s|%s|%d",
                MSG_DISCOVERY, node.getNodeId(), node.getHost(), node.getPort()));
    }

    private void sendResponse() {
        sendMulticast(String.format("%s|%s|%s|%d",
                MSG_RESPONSE, node.getNodeId(), node.getHost(), node.getPort()));
    }

    private void sendMulticast(String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, groupAddress, config.getDiscoveryPort());
            multicastSocket.send(packet);
        } catch (IOException e) {
            logger.debug("Failed to send discovery packet: {}", e.getMessage());
        }
    }

    public void shutdown() {
        isRunning.set(false);
        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                multicastSocket.leaveGroup(new InetSocketAddress(groupAddress, config.getDiscoveryPort()), null);
                multicastSocket.close();
            } catch (IOException e) {
              // expected on shutdown
            }
        }
        listenerService.shutdownNow();
        broadcasterService.shutdownNow();
    }
}