package com.p2p.filesystem.cli;

import com.p2p.filesystem.core.EnhancedP2PNode;
import com.p2p.filesystem.core.P2PNode;

import java.nio.file.Paths;
import java.util.Scanner;

public class P2PCLIInterface {
    private final P2PNode node;
    private final Scanner scanner;
    private volatile boolean isRunning;

    public P2PCLIInterface(P2PNode node) {
        this.node = node;
        this.scanner = new Scanner(System.in);
        this.isRunning = false;
    }

    public void start() {
        isRunning = true;
        System.out.println("\n=== P2P Node CLI (" + node.getNodeId() + ") ===");
        System.out.println("Type 'help' for commands.");

        while (isRunning) {
            System.out.print("p2p> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (line.equals("exit")) { isRunning = false; break; }
            handleCommand(line);
        }
    }

    private void handleCommand(String line) {
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();
        try {
            switch (cmd) {
                case "help": showHelp(); break;
                case "status": showStatus(); break;
                case "add": if (parts.length > 1) addFile(parts[1]); break;
                case "download": if (parts.length > 2) downloadFile(parts[1], parts[2]); break;
                case "stats": showStats(); break;
                case "monitor": startMonitorLoop(); break;
                default: System.out.println("Unknown command.");
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private void showHelp() {
        System.out.println("Commands: status, add <path>, download <id> <out>, stats, monitor, exit");
    }

    private void addFile(String pathStr) {
        try {
            String id = node.addFile(Paths.get(pathStr));
            System.out.println("Indexed file ID: " + id);
        } catch (Exception e) {
            System.out.println("Failed: " + e.getMessage());
        }
    }

    private void downloadFile(String fileId, String outStr) {
        System.out.println("Downloading " + fileId + "...");
        node.downloadFile(fileId, Paths.get(outStr)).thenAccept(success ->
                System.out.println(success ? "Download complete!" : "Download failed."));
    }

    private void showStatus() {
        System.out.printf("Peers: %d | Files: %d%n", node.getPeers().size(), node.getKnownFiles().size());
        System.out.println("Storage: " + node.getChunkStorage().getStats());
    }

    private void showStats() {
        if (node instanceof EnhancedP2PNode) {
            System.out.println(((EnhancedP2PNode) node).getPerformanceMonitor().getSnapshot());
        } else {
            System.out.println("Enhanced stats unavailable.");
        }
    }

    private void startMonitorLoop() {
        System.out.println("Monitor mode (Ctrl+C to stop)...");
        try {
            while (true) {
                showStatus();
                Thread.sleep(2000);
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (InterruptedException e) {
            System.out.println("Monitor stopped.");
        }
    }
}