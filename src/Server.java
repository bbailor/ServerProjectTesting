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

    //Max threads
    static final ExecutorService clientPool = Executors.newFixedThreadPool(10);

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
            serverSocket.setReceiveBufferSize(1024 * 1024);
            System.out.println("[Server] TCP listening on port " + TCP_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] New client connected: " + clientSocket.getInetAddress());

                // Spawn a client-thread and go back to accepting
                clientPool.submit(new ClientHandler(clientSocket));
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

            try {
                socket.setSoTimeout(600_000);
                socket.setTcpNoDelay(true);

                socket.setReceiveBufferSize(1024 * 1024); // add this
                socket.setSendBufferSize(1024 * 1024);    // add this

                InputStream  rawIn  = socket.getInputStream();
                OutputStream rawOut = socket.getOutputStream();

                String line;
                while ((line = readLine(rawIn)) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String logLine = line.length() > 100
                        ? line.substring(0, 100) + "...[shortened]" : line;
                    System.out.println("[ClientThread] From client: " + logLine);

                    if (line.equals("LIST")) {
                        handleList(rawOut);
                    } else if (line.startsWith("REQUEST|")) {
                        handleRequest(line, rawIn, rawOut);
                    } else {
                        writeLine(rawOut, "ERROR|Unknown command: " + line);
                        rawOut.flush();
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[ClientThread] Client timed out: " + clientAddr);
            } catch (Exception e) {
                System.err.println("[ClientThread] Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.println("[ClientThread] Client disconnected: " + clientAddr);
            }
        }

        void handleList(OutputStream out) throws IOException {
            if (registry.isEmpty()) {
                writeLine(out, "ERROR|No services currently available");
            } else {
                String services = String.join(",", registry.keySet());
                writeLine(out, "SERVICES|" + services);
                System.out.println("[ClientThread] Sent service list: " + services);
            }
            out.flush();
        }

        void handleRequest(String line, InputStream clientIn, OutputStream clientOut) throws IOException {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) {
                writeLine(clientOut, "ERROR|Malformed request. Use: REQUEST|SERVICE|LENGTH");
                clientOut.flush();
                return;
            }

            String serviceName = parts[1].toUpperCase();

            // Determine if length-prefixed or legacy text request
            long dataLen;
            byte[] legacyBytes = null;
            try {
                dataLen = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                legacyBytes = parts[2].getBytes("UTF-8");
                dataLen = legacyBytes.length;
            }

            // Look up which SN offers this service
            NodeInfo node = registry.get(serviceName);
            if (node == null) {
                if (legacyBytes == null) skipBytes(clientIn, dataLen);
                writeLine(clientOut, "ERROR|Service not available: " + serviceName);
                clientOut.flush();
                return;
            }

            // Check the node hasn't gone stale
            if (System.currentTimeMillis() - node.lastSeen > NODE_TIMEOUT_MS) {
                registry.remove(serviceName);
                if (legacyBytes == null) skipBytes(clientIn, dataLen);
                writeLine(clientOut, "ERROR|Service node timed out: " + serviceName);
                clientOut.flush();
                return;
            }

            System.out.println("[ClientThread] Forwarding " + dataLen + " bytes to SN at "
                + node.ip + ":" + node.tcpPort);

            try (Socket snSocket = new Socket()) {
                snSocket.connect(new InetSocketAddress(node.ip, node.tcpPort), 30_000);
                snSocket.setSoTimeout(600_000);
                snSocket.setTcpNoDelay(true);

                InputStream  snIn  = snSocket.getInputStream();
                OutputStream snOut = snSocket.getOutputStream();

                // Send TASK|LENGTH to SN
                writeLine(snOut, "TASK|" + dataLen);
                snOut.flush();

                // Stream bytes to SN
                if (legacyBytes != null) {
                    snOut.write(legacyBytes);
                } else {
                    streamBytes(clientIn, snOut, dataLen);

snOut.flush();
System.out.println("[ClientThread] Finished streaming " + dataLen + " bytes to SN");


                }
                snOut.flush();

                // Read RESULT|LENGTH back from SN
                String snHeader = readLine(snIn);
                if (snHeader == null || snHeader.startsWith("ERROR|")) {
                    writeLine(clientOut, snHeader != null ? snHeader : "ERROR|No response from service node");
                    clientOut.flush();
                    return;
                }

                if (!snHeader.startsWith("RESULT|")) {
                    writeLine(clientOut, "ERROR|Unexpected SN response: " + snHeader);
                    clientOut.flush();
                    return;
                }

                long resultLen = Long.parseLong(snHeader.split("\\|")[1]);

                // Forward RESULT|LENGTH to client then stream result bytes
                writeLine(clientOut, "RESULT|" + resultLen);
                clientOut.flush();
                streamBytes(snIn, clientOut, resultLen);
                clientOut.flush();

                System.out.println("[ClientThread] Forwarded " + resultLen + " bytes back to client");

            } catch (SocketTimeoutException e) {
                writeLine(clientOut, "ERROR|Service node timed out during execution");
                clientOut.flush();
                registry.remove(serviceName);
            } catch (ConnectException e) {
                writeLine(clientOut, "ERROR|Could not connect to service node");
                clientOut.flush();
                registry.remove(serviceName);
            } catch (Exception e) {
                writeLine(clientOut, "ERROR|" + e.getMessage());
                clientOut.flush();
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
        long   lastSeen;

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

    // NEW stream meathods that can be used by both client and SN threads to read/write lines and stream data.

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    static void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
    }

    static void streamBytes(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read == -1) throw new EOFException("Stream ended early");
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    static void skipBytes(InputStream in, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read == -1) break;
            remaining -= read;
        }
    }
}