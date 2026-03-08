import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SERVER
 * 
 * Runs on: EC2 Instance
 * 
 * What this does:
 *   - UDP thread listens for heartbeats from Service Nodes
 *   - TCP thread accepts client connections and spawns a client-thread per client
 *   - Client-thread looks up which SN offers the requested service, forwards the
 *     request to that SN over TCP, and sends the result back to the client
 */
public class Server {

    // Ports this server listens on
    static final int TCP_PORT     = 9000;   // clients connect here
    static final int UDP_HB_PORT  = 9001;   // service nodes send heartbeats here

    // How long (ms) before a node is considered dead
    static final long NODE_TIMEOUT_MS = 120_000;

    // Thread-safe registry: serviceName -> NodeInfo
    // ConcurrentHashMap so the heartbeat thread and client-threads can both
    // access it without explicit locking
    static final ConcurrentHashMap<String, NodeInfo> registry = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        System.out.println("[Server] Starting...");

        // Start UDP heartbeat listener in its own thread
        Thread hbThread = new Thread(Server::heartbeatListener, "HeartbeatThread");

        // Garbage collection and thread management. Background thread
        hbThread.setDaemon(true);
        hbThread.start();

        // Start cleanup thread — removes dead nodes every 5 seconds
        // independent of whether any heartbeats are arriving
        startCleanupThread();

        // Start TCP server — this is the main accept loop
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            System.out.println("[Server] TCP listening on port " + TCP_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] New client connected: " + clientSocket.getInetAddress());

                // Spawn a client-thread and go back to accepting
                Thread clientThread = new Thread(
                    new ClientHandler(clientSocket), "ClientThread-" + clientSocket.getPort()
                );
                clientThread.start();
            }
        }
    }

    // -------------------------------------------------------------------------
    // UDP Heartbeat Listener
    // Runs forever, receiving UDP packets from Service Nodes.
    // Expected packet format:  HEARTBEAT|<nodeId>|<serviceName>|<tcpPort>
    // Example:                 HEARTBEAT|SN-Base64|BASE64|9100
    // -------------------------------------------------------------------------
    static void heartbeatListener() {
        System.out.println("[HeartbeatThread] UDP listening on port " + UDP_HB_PORT);
        try (DatagramSocket udpSocket = new DatagramSocket(UDP_HB_PORT)) {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);

                String msg = new String(packet.getData(), 0, packet.getLength()).trim();
                String senderIp = packet.getAddress().getHostAddress();
                System.out.println("[HeartbeatThread] Received: " + msg + " from " + senderIp);

                // Parse:  HEARTBEAT|nodeId|serviceName|tcpPort
                String[] parts = msg.split("\\|");
                if (parts.length == 4 && parts[0].equals("HEARTBEAT")) {
                    String nodeId      = parts[1];
                    String serviceName = parts[2].toUpperCase();
                    int    tcpPort     = Integer.parseInt(parts[3]);

                    NodeInfo info = new NodeInfo(nodeId, senderIp, tcpPort, serviceName);
                    registry.put(serviceName, info);
                    System.out.println("[HeartbeatThread] Registered/refreshed: " + info);
                }
            }
        } catch (Exception e) {
            System.err.println("[HeartbeatThread] Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup Thread
    // Runs every 5 seconds and removes any nodes that haven't sent a heartbeat
    // within NODE_TIMEOUT_MS. This runs independently of the heartbeat listener
    // so dead nodes are removed even when no heartbeats are arriving.
    // -------------------------------------------------------------------------
    static void startCleanupThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5_000); // check every 5 seconds
                    long now = System.currentTimeMillis();
                    registry.entrySet().removeIf(e -> {
                        boolean dead = (now - e.getValue().lastSeen) > NODE_TIMEOUT_MS;
                        if (dead) System.out.println("[Cleanup] Node DEAD, removing: " + e.getKey());
                        return dead;
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "CleanupThread");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // ClientHandler — one instance per connected client (runs in its own thread)
    // 
    // Protocol with client:
    //   Client sends:   LIST
    //   Server replies: SERVICE:BASE64,SERVICE:CSV,...
    //
    //   Client sends:   REQUEST|<serviceName>|<input text>
    //   Server replies: RESULT|<output>   or   ERROR|<reason>
    // -------------------------------------------------------------------------
    static class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            String clientAddr = socket.getInetAddress().getHostAddress();
            System.out.println("[ClientThread] Handling client: " + clientAddr);

            try (
                BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter    out = new PrintWriter(socket.getOutputStream(), true)
            ) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    String logLine = line.length() > 100 ? line.substring(0, 100) + "...[shortened]" : line;
                    System.out.println("[ClientThread] From client: " + logLine);

                    if (line.equals("LIST")) {
                        // Send back all currently alive services
                        handleList(out);

                    } else if (line.startsWith("REQUEST|")) {
                        // REQUEST|<serviceName>|<input>
                        handleRequest(line, out);

                    } else {
                        out.println("ERROR|Unknown command: " + line);
                    }
                }
            } catch (Exception e) {
                System.err.println("[ClientThread] Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.println("[ClientThread] Client disconnected: " + clientAddr);
            }
        }

        void handleList(PrintWriter out) {
            if (registry.isEmpty()) {
                out.println("ERROR|No services currently available");
                return;
            }
            // Build response like: SERVICES|BASE64,CSV,HMAC
            String services = String.join(",", registry.keySet());
            out.println("SERVICES|" + services);
            System.out.println("[ClientThread] Sent service list: " + services);
        }

        void handleRequest(String line, PrintWriter out) {
            // Parse:  REQUEST|BASE64|hello world
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) {
                out.println("ERROR|Malformed request. Use: REQUEST|SERVICE|input");
                return;
            }

            String serviceName = parts[1].toUpperCase();
            String input       = parts[2];

            // Look up which SN offers this service
            NodeInfo node = registry.get(serviceName);
            if (node == null) {
                out.println("ERROR|Service not available: " + serviceName);
                return;
            }

            // Check the node hasn't gone stale since last heartbeat
            if (System.currentTimeMillis() - node.lastSeen > NODE_TIMEOUT_MS) {
                registry.remove(serviceName);
                out.println("ERROR|Service node timed out: " + serviceName);
                return;
            }

            System.out.println("[ClientThread] Forwarding to SN at " + node.ip + ":" + node.tcpPort);

            // Forward to the Service Node over TCP
            try (
                Socket snSocket = new Socket(node.ip, node.tcpPort);
                PrintWriter snOut = new PrintWriter(snSocket.getOutputStream(), true);
                BufferedReader snIn = new BufferedReader(new InputStreamReader(snSocket.getInputStream()))
            ) {
                snSocket.setSoTimeout(10_000); // 10 second timeout waiting for SN

                // Send request to SN:  TASK|<input>
                snOut.println("TASK|" + input);

                // Wait for result from SN:  RESULT|<output>
                String snResponse = snIn.readLine();
                String logResponse = snResponse != null && snResponse.length() > 100 ? snResponse.substring(0, 100) + "...[shortened]" : snResponse;
                System.out.println("[ClientThread] SN responded: " + logResponse);

                // Forward result back to the client
                out.println(snResponse);

            } catch (SocketTimeoutException e) {
                out.println("ERROR|Service node timed out during execution");
                registry.remove(serviceName); // consider it dead
            } catch (ConnectException e) {
                out.println("ERROR|Could not connect to service node");
                registry.remove(serviceName);
            } catch (Exception e) {
                out.println("ERROR|" + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Simple data class to hold info about a registered Service Node
    // -------------------------------------------------------------------------
    static class NodeInfo {
        String nodeId;
        String ip;
        int    tcpPort;
        String service;
        long   lastSeen; // System.currentTimeMillis() when last heartbeat arrived

        NodeInfo(String nodeId, String ip, int tcpPort, String service) {
            this.nodeId   = nodeId;
            this.ip       = ip;
            this.tcpPort  = tcpPort;
            this.service  = service;
            this.lastSeen = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return nodeId + " @ " + ip + ":" + tcpPort + " [" + service + "]";
        }
    }
}