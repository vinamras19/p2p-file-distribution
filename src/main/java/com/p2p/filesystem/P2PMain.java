package com.p2p.filesystem;

import com.p2p.filesystem.api.DashboardAPIServer;
import com.p2p.filesystem.cli.P2PCLIInterface;
import com.p2p.filesystem.config.P2PConfiguration;
import com.p2p.filesystem.core.EnhancedP2PNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2PMain {
    private static final Logger logger = LoggerFactory.getLogger(P2PMain.class);

    public static void main(String[] args) {
        try {
            System.out.println("Initializing P2P System...");

            P2PConfiguration config = new P2PConfiguration();

            if (args.length > 0) {
                config.setNodePort(Integer.parseInt(args[0]));
            }

            EnhancedP2PNode node = new EnhancedP2PNode(config);
            node.start();

            int apiPort = config.getNodePort() + 100;
            DashboardAPIServer api = new DashboardAPIServer(apiPort, node);
            api.start();

            new P2PCLIInterface(node).start();

            System.out.println("Shutting down services...");
            node.shutdown();
            System.exit(0);

        } catch (Exception e) {
            logger.error("Fatal startup error", e);
            System.err.println("Startup failed: " + e.getMessage());
            System.exit(1);
        }
    }
}