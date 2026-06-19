package com.p2p.filesystem.network;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileAnnounceMessageTest {

    @Test
    void roundTripsFileIds() {
        List<String> ids = List.of("file-1", "file-2", "file-3");
        FileAnnounceMessage original = new FileAnnounceMessage("node-x", ids);

        byte[] bytes = original.serialize();
        P2PMessage decoded = P2PMessage.deserialize(bytes);

        assertEquals(P2PMessage.Type.FILE_ANNOUNCE, decoded.getType());
        assertEquals("node-x", decoded.getSenderId());
        assertEquals(ids, ((FileAnnounceMessage) decoded).getFileIds());
    }

    @Test
    void roundTripsEmptyList() {
        FileAnnounceMessage original = new FileAnnounceMessage("node-y", List.of());
        P2PMessage decoded = P2PMessage.deserialize(original.serialize());
        assertEquals(0, ((FileAnnounceMessage) decoded).getFileIds().size());
    }
}