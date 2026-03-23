import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SERVER
 *
 * Runs on: EC2 Instance
 *
 * Protocol:
 *   Client sends:   LIST\n
 *   Server replies: SERVICES|CSV,HMAC,...\n
 *
 *   Client sends:   REQUEST|SERVICE|LENGTH\n<raw bytes>
 *   Server replies: RESULT|LENGTH\n<raw bytes>
 *                or ERROR|<reason>\n
 */
public class Server {

    static final int  TCP_PORT        = 9000;
    static final int  UDP_HB_PORT     = 9001;
    static final long NODE_TIMEOUT_MS = 120_000;

    static final ConcurrentHashMap<String, NodeInfo> registry   = new ConcurrentHashMap<>();
    static final ExecutorService                      clientPool = Executors.newFixedThreadPool(10);

    public static void main(String[] args) throws Exception {
        System.out.println("[Server] Starting...");

        Thread hbThread = new Thread(Server::heartbeatListener, "HeartbeatThread");
        hbThread.setDaemon(true);
        hbThread.start();

        startCleanupThread();

        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT, 100)) {
            serverSocket.setReceiveBufferSize(1024 * 1024);
            System.out.println("[Server] TCP listening on port " + TCP_PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] Client connected: " + clientSocket.getInetAddress());
                clientPool.submit(new ClientHandler(clientSocket));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Heartbeat Listener
    // -------------------------------------------------------------------------
    static void heartbeatListener() {
        System.out.println("[HeartbeatThread] UDP listening on port " + UDP_HB_PORT);
        try (DatagramSocket udpSocket = new DatagramSocket(UDP_HB_PORT)) {
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                udpSocket.receive(packet);
                String msg      = new String(packet.getData(), 0, packet.getLength()).trim();
                String senderIp = packet.getAddress().getHostAddress();
                System.out.println("[HeartbeatThread] Received: " + msg + " from " + senderIp);
                String[] parts = msg.split("\\|");
                if (parts.length == 4 && parts[0].equals("HEARTBEAT")) {
                    String serviceName = parts[2].toUpperCase();
                    int    tcpPort     = Integer.parseInt(parts[3]);
                    registry.put(serviceName, new NodeInfo(parts[1], senderIp, tcpPort, serviceName));
                    System.out.println("[HeartbeatThread] Registered/refreshed: " + serviceName
                        + " @ " + senderIp + ":" + tcpPort);
                }
            }
        } catch (Exception e) {
            System.err.println("[HeartbeatThread] Error: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup Thread
    // -------------------------------------------------------------------------
    static void startCleanupThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5_000);
                    long now = System.currentTimeMillis();
                    registry.entrySet().removeIf(e -> {
                        boolean dead = (now - e.getValue().lastSeen) > NODE_TIMEOUT_MS;
                        if (dead) System.out.println("[Cleanup] Node DEAD: " + e.getKey());
                        return dead;
                    });
                } catch (InterruptedException e) { break; }
            }
        }, "CleanupThread");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Client Handler
    // -------------------------------------------------------------------------
    static class ClientHandler implements Runnable {
        private final Socket socket;
        ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            String clientAddr = socket.getInetAddress().getHostAddress();
            System.out.println("[ClientThread] Handling: " + clientAddr);

            // Use a single DataInputStream for ALL reads from this client.
            // This is critical — never mix DataInputStream with raw InputStream
            // or bytes will be lost in the buffer.
            DataInputStream  clientIn;
            DataOutputStream clientOut;
            try {
                clientIn  = new DataInputStream(socket.getInputStream());
                clientOut = new DataOutputStream(socket.getOutputStream());
                socket.setSoTimeout(1_800_000);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(1024 * 1024);
                socket.setSendBufferSize(1024 * 1024);
            } catch (IOException e) {
                System.err.println("[ClientThread] Setup error: " + e.getMessage());
                return;
            }

            try {
                String line;
                while ((line = readLine(clientIn)) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    String logLine = line.length() > 100 ? line.substring(0, 100) + "..." : line;
                    System.out.println("[ClientThread] From client: " + logLine);

                    if (line.equals("LIST")) {
                        handleList(clientOut);
                    } else if (line.startsWith("REQUEST|")) {
                        handleRequest(line, clientIn, clientOut);
                    } else {
                        writeLine(clientOut, "ERROR|Unknown command: " + line);
                    }
                }
            } catch (SocketTimeoutException e) {
                System.out.println("[ClientThread] Timeout: " + clientAddr);
            } catch (Exception e) {
                System.err.println("[ClientThread] Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
                System.out.println("[ClientThread] Disconnected: " + clientAddr);
            }
        }

        void handleList(DataOutputStream out) throws IOException {
            if (registry.isEmpty()) {
                writeLine(out, "ERROR|No services currently available");
            } else {
                writeLine(out, "SERVICES|" + String.join(",", registry.keySet()));
            }
        }

        void handleRequest(String line, DataInputStream clientIn, DataOutputStream clientOut) throws IOException {
            // Parse: REQUEST|SERVICE|LENGTH
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) {
                writeLine(clientOut, "ERROR|Malformed request");
                return;
            }

            String serviceName = parts[1].toUpperCase();

            // Parse length — fall back to legacy text mode if not a number
            long   dataLen;
            byte[] legacyBytes = null;
            try {
                dataLen = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                legacyBytes = parts[2].getBytes("UTF-8");
                dataLen     = legacyBytes.length;
            }

            // Look up node
            NodeInfo node = registry.get(serviceName);
            if (node == null) {
                if (legacyBytes == null) skipBytes(clientIn, dataLen);
                writeLine(clientOut, "ERROR|Service not available: " + serviceName);
                return;
            }

            if (System.currentTimeMillis() - node.lastSeen > NODE_TIMEOUT_MS) {
                registry.remove(serviceName);
                if (legacyBytes == null) skipBytes(clientIn, dataLen);
                writeLine(clientOut, "ERROR|Service node timed out: " + serviceName);
                return;
            }

            System.out.println("[ClientThread] Forwarding " + formatSize(dataLen)
                + " to SN at " + node.ip + ":" + node.tcpPort);

            // Connect to SN using a DataOutputStream/DataInputStream pair
            try (Socket snSocket = new Socket()) {
                snSocket.connect(new InetSocketAddress(node.ip, node.tcpPort), 30_000);
                snSocket.setSoTimeout(1_800_000);
                snSocket.setTcpNoDelay(true);
                snSocket.setReceiveBufferSize(1024 * 1024);
                snSocket.setSendBufferSize(1024 * 1024);

                DataInputStream  snIn  = new DataInputStream(snSocket.getInputStream());
                DataOutputStream snOut = new DataOutputStream(snSocket.getOutputStream());

                // Send TASK|LENGTH header to SN
                writeLine(snOut, "TASK|" + dataLen);

                // Forward data bytes to SN
                if (legacyBytes != null) {
                    snOut.write(legacyBytes);
                } else {
                    streamBytes(clientIn, snOut, dataLen);
                }
                snOut.flush();
                System.out.println("[ClientThread] Finished streaming to SN");

                // Read RESULT|LENGTH from SN
                String snHeader = readLine(snIn);
                System.out.println("[ClientThread] SN response header: " + snHeader);

                if (snHeader == null || snHeader.startsWith("ERROR|")) {
                    writeLine(clientOut, snHeader != null ? snHeader : "ERROR|No response from SN");
                    return;
                }
                if (!snHeader.startsWith("RESULT|")) {
                    writeLine(clientOut, "ERROR|Unexpected SN response: " + snHeader);
                    return;
                }

                long resultLen = Long.parseLong(snHeader.split("\\|")[1]);

                // Forward result back to client
                writeLine(clientOut, "RESULT|" + resultLen);
                streamBytes(snIn, clientOut, resultLen);
                clientOut.flush();

                System.out.println("[ClientThread] Done. Result: " + formatSize(resultLen));

            } catch (SocketTimeoutException e) {
                writeLine(clientOut, "ERROR|Service node timed out during execution");
                registry.remove(serviceName);
            } catch (ConnectException e) {
                writeLine(clientOut, "ERROR|Could not connect to service node");
                registry.remove(serviceName);
            } catch (Exception e) {
                System.err.println("[ClientThread] SN error: " + e.getMessage());
                writeLine(clientOut, "ERROR|" + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // NodeInfo
    // -------------------------------------------------------------------------
    static class NodeInfo {
        String nodeId, ip, service;
        int    tcpPort;
        long   lastSeen;
        NodeInfo(String n, String i, int p, String s) {
            nodeId = n; ip = i; tcpPort = p; service = s;
            lastSeen = System.currentTimeMillis();
        }
    }

    // -------------------------------------------------------------------------
    // Utilities — all use DataInputStream/DataOutputStream
    // -------------------------------------------------------------------------

    /**
     * Read a line from a DataInputStream one byte at a time.
     * Strips \r and stops at \n.
     * Returns null if stream is closed.
     */
    static String readLine(DataInputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    /**
     * Write a line followed by \n to a DataOutputStream.
     */
    static void writeLine(DataOutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
        out.flush();
    }

    /**
     * Stream exactly length bytes from in to out in 64KB chunks.
     */
    static void streamBytes(DataInputStream in, DataOutputStream out, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long   rem = length;
        while (rem > 0) {
            int toRead = (int) Math.min(buf.length, rem);
            int read   = in.read(buf, 0, toRead);
            if (read == -1) throw new EOFException("Stream ended with " + rem + " bytes remaining");
            out.write(buf, 0, read);
            rem -= read;
        }
    }

    /**
     * Skip exactly length bytes from a DataInputStream.
     */
    static void skipBytes(DataInputStream in, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long   rem = length;
        while (rem > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (r == -1) break;
            rem -= r;
        }
    }

    static String formatSize(long b) {
        if (b < 1024)        return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return                      String.format("%.1f MB", b / 1024.0 / 1024.0);
    }
}