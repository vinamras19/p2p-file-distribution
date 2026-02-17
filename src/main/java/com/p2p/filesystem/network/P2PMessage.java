package com.p2p.filesystem.network;

import java.io.*;
import java.nio.charset.StandardCharsets;

public abstract class P2PMessage {
    public enum Type { HANDSHAKE, HEARTBEAT, CHUNK_REQUEST, CHUNK_RESPONSE, PEER_DISCOVERY }

    protected final Type type;
    protected final String senderId;

    public P2PMessage(Type type, String senderId) {
        this.type = type;
        this.senderId = senderId;
    }

    // Binary Protocol - Format: [Type(1)][SenderLen(2)][SenderBytes][Data]

    public byte[] serialize() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeByte(type.ordinal());
            writeString(dos, senderId);
            writePayload(dos);

            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public static P2PMessage deserialize(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            int typeOrdinal = dis.readByte();
            if (typeOrdinal < 0 || typeOrdinal >= Type.values().length) {
                throw new IOException("Unknown message type: " + typeOrdinal);
            }

            Type type = Type.values()[typeOrdinal];
            String senderId = readString(dis);

            switch (type) {
                case HANDSHAKE: return new HandshakeMessage(senderId, dis);
                case HEARTBEAT: return new HeartbeatMessage(senderId);
                case CHUNK_REQUEST: return new ChunkRequestMessage(senderId, dis);
                case CHUNK_RESPONSE: return new ChunkResponseMessage(senderId, dis);
                default: throw new IOException("Handler not implemented for: " + type);
            }
        } catch (IOException e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    protected abstract void writePayload(DataOutputStream dos) throws IOException;


    protected void writeString(DataOutputStream dos, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        dos.writeShort(b.length);
        dos.write(b);
    }

    protected static String readString(DataInputStream dis) throws IOException {
        int len = dis.readShort();
        byte[] b = new byte[len];
        dis.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public Type getType() { return type; }
    public String getSenderId() { return senderId; }
}