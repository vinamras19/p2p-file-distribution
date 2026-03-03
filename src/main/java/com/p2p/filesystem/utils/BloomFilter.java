package com.p2p.filesystem.utils;

import java.util.BitSet;
import java.util.zip.CRC32;

public class BloomFilter {
    private final BitSet bitSet;
    private final int size;
    private final int hashCount;

    public BloomFilter(int expectedElements, double falsePositiveRate) {
        this.size = (int) (-expectedElements * Math.log(falsePositiveRate) / (Math.log(2) * Math.log(2)));
        this.hashCount = Math.max(1, (int) ((size / expectedElements) * Math.log(2)));
        this.bitSet = new BitSet(size);
    }

    public void add(String key) {
        int[] hashes = createHashes(key);
        for (int h : hashes) {
            bitSet.set(Math.abs(h % size));
        }
    }

    public boolean mightContain(String key) {
        int[] hashes = createHashes(key);
        for (int h : hashes) {
            if (!bitSet.get(Math.abs(h % size))) {
                return false;
            }
        }
        return true;
    }

    private int[] createHashes(String key) {
        int[] result = new int[hashCount];
        int h1 = key.hashCode(); // Double Hashing [hash1 + i * hash2]
        CRC32 crc = new CRC32();
        crc.update(key.getBytes());
        int h2 = (int) crc.getValue();

        for (int i = 0; i < hashCount; i++) {
            result[i] = h1 + i * h2;
        }
        return result;
    }
}