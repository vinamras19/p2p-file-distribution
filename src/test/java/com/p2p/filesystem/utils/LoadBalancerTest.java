package com.p2p.filesystem.utils;

import com.p2p.filesystem.core.PeerInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LoadBalancerTest {

    private PeerInfo peer(String id) {
        return new PeerInfo(id, "localhost", 9000);
    }

    @Test
    void selectReturnsNullForEmpty() {
        LoadBalancer lb = new LoadBalancer();
        assertNull(lb.selectPeer(List.of()));
        assertNull(lb.selectPeer(null));
    }

    @Test
    void selectReturnsSolePeer() {
        LoadBalancer lb = new LoadBalancer();
        PeerInfo only = peer("a");
        assertEquals(only, lb.selectPeer(List.of(only)));
    }

    @Test
    void prefersLeastLoadedPeer() {
        LoadBalancer lb = new LoadBalancer();
        PeerInfo a = peer("a");
        PeerInfo b = peer("b");

        lb.recordRequest("a");
        lb.recordRequest("a");
        lb.recordRequest("a");

        assertEquals(b, lb.selectPeer(List.of(a, b)));
    }

    @Test
    void avoidsHighLatencyPeer() {
        LoadBalancer lb = new LoadBalancer();
        PeerInfo a = peer("a");
        PeerInfo b = peer("b");

        lb.recordRequest("a");
        lb.recordResponse("a", 4000, true);

        assertEquals(b, lb.selectPeer(List.of(a, b)));
    }

    @Test
    void excludesDeadPeer() {
        LoadBalancer lb = new LoadBalancer();
        PeerInfo a = peer("a");
        PeerInfo b = peer("b");
        a.setAlive(false);

        assertEquals(b, lb.selectPeer(List.of(a, b)));
    }

    @Test
    void circuitBreakerExcludesFailingPeerThenRecovers() {
        LoadBalancer lb = new LoadBalancer();
        PeerInfo a = peer("a");
        PeerInfo b = peer("b");

        for (int i = 0; i < 6; i++) {
            lb.recordRequest("a");
            lb.recordResponse("a", 100, false);
        }
        assertEquals(b, lb.selectPeer(List.of(a, b)));

        for (int i = 0; i < 6; i++) {
            lb.recordRequest("a");
            lb.recordResponse("a", 100, true);
        }
        assertNotNull(lb.selectPeer(List.of(a, b)));
    }
}