package com.p2p.filesystem.security;

import com.p2p.filesystem.config.P2PConfiguration;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.security.GeneralSecurityException;

public class SecureChannelHandler {
    private static final Logger logger = LoggerFactory.getLogger(SecureChannelHandler.class);

    private final P2PConfiguration config;
    private SslContext serverContext;
    private SslContext clientContext;

    public SecureChannelHandler(P2PConfiguration config) {
        this.config = config;
    }

    public void initialize() throws GeneralSecurityException, IOException {
        if (!config.isEnableEncryption()) {
            return;
        }

        SelfSignedCertificate ssc = new SelfSignedCertificate("p2p-node");

        serverContext = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
                .protocols("TLSv1.3", "TLSv1.2")
                .build();

        clientContext = SslContextBuilder.forClient()
                .protocols("TLSv1.3", "TLSv1.2")
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

        logger.info("TLS 1.3 Security Context initialized.");
    }

    public void applyToServer(SocketChannel ch) {
        if (serverContext != null) {
            SSLEngine engine = serverContext.newEngine(ch.alloc());
            engine.setUseClientMode(false);

            if (config.isVerifyPeerCertificates()) {
                engine.setNeedClientAuth(true);
            }

            ch.pipeline().addFirst("ssl", new SslHandler(engine));
        }
    }

    public void applyToClient(SocketChannel ch, String host, int port) {
        if (clientContext != null) {
            SSLEngine engine = clientContext.newEngine(ch.alloc(), host, port);
            engine.setUseClientMode(true);
            ch.pipeline().addFirst("ssl", new SslHandler(engine));
        }
    }

    public void shutdown() {
        serverContext = null;
        clientContext = null;
    }
}