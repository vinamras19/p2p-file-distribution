package com.p2p.filesystem.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class HandshakeMessage extends P2PMessage {
    private final String version;
    private final String userAgent;

    public HandshakeMessage(String senderId, String version, String userAgent) {
        super(Type.HANDSHAKE, senderId);
        this.version = version;
        this.userAgent = userAgent;
    }

    public HandshakeMessage(String senderId, DataInputStream dis) throws IOException {
        super(Type.HANDSHAKE, senderId);
        this.version = readString(dis);
        this.userAgent = readString(dis);
    }

    @Override
    protected void writePayload(DataOutputStream dos) throws IOException {
        writeString(dos, version);
        writeString(dos, userAgent);
    }
}