package com.p2p.filesystem.network;

import java.io.DataOutputStream;

public class HeartbeatMessage extends P2PMessage {
    public HeartbeatMessage(String senderId) {
        super(Type.HEARTBEAT, senderId);
    }

    @Override
    protected void writePayload(DataOutputStream dos) {
    }
}