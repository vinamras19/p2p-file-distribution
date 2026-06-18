    package com.p2p.filesystem.network;

    import com.p2p.filesystem.config.P2PConfiguration;
    import com.p2p.filesystem.core.P2PNode;
    import io.netty.bootstrap.Bootstrap;
    import io.netty.bootstrap.ServerBootstrap;
    import io.netty.buffer.Unpooled;
    import io.netty.channel.*;
    import io.netty.channel.nio.NioEventLoopGroup;
    import io.netty.channel.socket.SocketChannel;
    import io.netty.channel.socket.nio.NioServerSocketChannel;
    import io.netty.channel.socket.nio.NioSocketChannel;
    import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
    import io.netty.handler.codec.LengthFieldPrepender;
    import io.netty.handler.timeout.IdleStateEvent;
    import io.netty.handler.timeout.IdleStateHandler;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;

    import java.util.Map;
    import java.util.ArrayList;
    import java.util.concurrent.*;

    public class P2PNetworkHandler {
        private static final Logger logger = LoggerFactory.getLogger(P2PNetworkHandler.class);
        private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;
        private static final int IDLE_TIMEOUT = 300;

        private final P2PNode node;
        private final P2PConfiguration config;
        private final Map<String, Channel> connections = new ConcurrentHashMap<>();

        private EventLoopGroup bossGroup;
        private EventLoopGroup workerGroup;

        public P2PNetworkHandler(P2PNode node, P2PConfiguration config) {
            this.node = node;
            this.config = config;
        }

        public void start() {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            try {
                ServerBootstrap b = new ServerBootstrap();
                b.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new PipelineInitializer(true))
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true);

                b.bind(config.getNodePort()).sync();
                logger.info("P2P Network listening on port {}", config.getNodePort());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Interrupted during startup", e);
            }
        }

        public CompletableFuture<Channel> connectToPeer(String host, int port) {
            String key = host + ":" + port;
            if (connections.containsKey(key) && connections.get(key).isActive()) {
                return CompletableFuture.completedFuture(connections.get(key));
            }

            CompletableFuture<Channel> future = new CompletableFuture<>();
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .handler(new PipelineInitializer(false, host, port))
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

            b.connect(host, port).addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    Channel ch = f.channel();
                    connections.put(key, ch);
                    sendHandshake(ch);
                    sendAnnounce(ch);
                    future.complete(ch);
                } else {
                    future.completeExceptionally(f.cause());
                }
            });

            return future;
        }

        public void sendMessage(Channel ch, P2PMessage msg) {
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(Unpooled.wrappedBuffer(msg.serialize()));
            }
        }

        public void broadcast(P2PMessage msg) {
            connections.values().forEach(ch -> sendMessage(ch, msg));
        }

        private void sendHandshake(Channel ch) {
            sendMessage(ch, new HandshakeMessage(node.getNodeId(), "1.0", "JavaP2P"));
        }

        private void sendAnnounce(Channel ch) {
            sendMessage(ch, new FileAnnounceMessage(node.getNodeId(), new ArrayList<>(node.getKnownFiles().keySet())));
        }

        public void shutdown() {
            connections.values().forEach(Channel::close);
            connections.clear();
            if (bossGroup != null) bossGroup.shutdownGracefully();
            if (workerGroup != null) workerGroup.shutdownGracefully();
        }

        private class PipelineInitializer extends ChannelInitializer<SocketChannel> {
            private final boolean isServer;
            private final String host;
            private final int port;

            PipelineInitializer(boolean isServer) {
                this(isServer, null, 0);
            }

            PipelineInitializer(boolean isServer, String host, int port) {
                this.isServer = isServer;
                this.host = host;
                this.port = port;
            }

            @Override
            protected void initChannel(SocketChannel ch) {
                if (isServer) {
                    node.getSecureChannel().applyToServer(ch);
                } else {
                    node.getSecureChannel().applyToClient(ch, host, port);
                }
                ChannelPipeline p = ch.pipeline();
                p.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_SIZE, 0, 4, 0, 4));
                p.addLast(new LengthFieldPrepender(4));
                p.addLast(new IdleStateHandler(IDLE_TIMEOUT, IDLE_TIMEOUT, 0));
                p.addLast(new P2PHandlerAdapter());
            }
        }

        private class P2PHandlerAdapter extends ChannelInboundHandlerAdapter {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                io.netty.buffer.ByteBuf buf = (io.netty.buffer.ByteBuf) msg;
                try {
                    byte[] bytes = new byte[buf.readableBytes()];
                    buf.readBytes(bytes);
                    P2PMessage p2pMsg = P2PMessage.deserialize(bytes);
                    node.handleMessage(p2pMsg, ctx.channel(), bytes.length);
                } catch (Exception e) {
                    logger.error("Protocol violation from {}", ctx.channel().remoteAddress(), e);
                    ctx.close();
                } finally {
                    buf.release();
                }
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof IdleStateEvent) {
                    sendMessage(ctx.channel(), new HeartbeatMessage(node.getNodeId()));
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                if (cause instanceof java.io.IOException) {
                    logger.debug("Peer disconnected: {}", ctx.channel().remoteAddress());
                } else {
                    logger.warn("Channel error", cause);
                }
                ctx.close();
            }
        }
    }