import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SERVICE NODE
 *
 * Parent class for all service nodes in the QU Microservices Cluster.
 *
 * Handles all shared functionality:
 *   - UDP heartbeat sender (random 15-30 second intervals)
 *   - TCP task listener with thread pool
 *   - New length-prefixed binary protocol (TASK|LENGTH\n<bytes>)
 *   - Port availability check
 *   - Socket timeout management
 *   - readLine / writeLine / streamBytes utilities
 *
 * Each service node only needs to:
 *   1. Set serverIp, myTcpPort, serviceName in main()
 *   2. Call init() at the end of main()
 *   3. Implement processTask(String input)
 *
 * Example subclass:
 *
 *   public class CSVServiceNode extends ServiceNode {
 *       public static void main(String[] args) throws Exception {
 *           if (args.length < 2) { ... }
 *           serverIp    = args[0];
 *           myTcpPort   = Integer.parseInt(args[1]);
 *           serviceName = "CSV";
 *           new CSVServiceNode().init();
 *       }
 *
 *       @Override
 *       String processTask(String input) {
 *           // CSV logic here
 *       }
 *   }
 */
public abstract class ServiceNode {

    // =========================================================================
    // SHARED STATE — set by each subclass in main() before calling init()
    // =========================================================================
    static String serverIp;
    static int    serverUdpPort = 9001;  // must match Server.UDP_HB_PORT
    static int    myTcpPort;
    static String serviceName;
    static String nodeId;

    // Thread pool — 8 threads handles up to 8 simultaneous clients
    static final ExecutorService threadPool = Executors.newFixedThreadPool(8);
    static final Random random = new Random();

    // =========================================================================
    // INIT — call this at the end of main() in each subclass
    // =========================================================================
    void init() throws Exception {
        nodeId = "SN-" + serviceName + "-" + myTcpPort;

        System.out.println("===========================================");
        System.out.println("   SERVICE NODE: " + serviceName);
        System.out.println("===========================================");
        System.out.println("[" + nodeId + "] Server:    " + serverIp + ":" + serverUdpPort);
        System.out.println("[" + nodeId + "] TCP Port:  " + myTcpPort);
        System.out.println("[" + nodeId + "] Threads:   8");
        System.out.println();

        // Check port is free before starting anything
        try (ServerSocket test = new ServerSocket(myTcpPort)) {
            System.out.println("[" + nodeId + "] Port " + myTcpPort + " is available.");
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] ERROR: Port " + myTcpPort + " is already in use. Exiting.");
            System.exit(1);
        }

        // Start heartbeat sender then TCP listener
        startHeartbeatSender();
        startTcpListener();
    }

    // =========================================================================
    // ABSTRACT METHOD — implement this in each subclass with service logic
    // =========================================================================
    abstract String processTask(String input) throws Exception;

    // =========================================================================
    // UDP HEARTBEAT SENDER
    // Sends HEARTBEAT|nodeId|serviceName|tcpPort to server every 15-30 seconds
    // =========================================================================
    void startHeartbeatSender() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatSender");
            t.setDaemon(true);
            return t;
        });

        // Send immediately on startup so server registers this node right away
        scheduler.execute(this::sendHeartbeat);
        scheduleNextHeartbeat(scheduler);
    }

    void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
        int delay = 15 + random.nextInt(16); // random 15-30 seconds
        scheduler.schedule(() -> {
            sendHeartbeat();
            scheduleNextHeartbeat(scheduler);
        }, delay, TimeUnit.SECONDS);
    }

    void sendHeartbeat() {
        try (DatagramSocket udp = new DatagramSocket()) {
            String msg  = "HEARTBEAT|" + nodeId + "|" + serviceName + "|" + myTcpPort;
            byte[] data = msg.getBytes("UTF-8");
            InetAddress addr = InetAddress.getByName(serverIp);
            udp.send(new DatagramPacket(data, data.length, addr, serverUdpPort));
            System.out.println("[" + nodeId + "] Heartbeat sent");
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Heartbeat failed: " + e.getMessage());
            // Do not crash — will retry on next scheduled heartbeat
        }
    }

    // =========================================================================
    // TCP TASK LISTENER
    // Accepts connections from the server and submits each to the thread pool
    // =========================================================================
    void startTcpListener() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(myTcpPort)) {
            System.out.println("[" + nodeId + "] Ready and waiting for tasks...\n");
            while (true) {
                Socket conn = serverSocket.accept();
                conn.setReceiveBufferSize(1024 * 1024);
                conn.setSendBufferSize(1024 * 1024);
                System.out.println("[" + nodeId + "] Connection from: "
                    + conn.getInetAddress().getHostAddress());
                threadPool.submit(() -> handleTask(conn));
            }
        }
    }

    // =========================================================================
    // TASK HANDLER
    //
    // New length-prefixed protocol:
    //   Server sends:   TASK|LENGTH\n<raw bytes>
    //   Node replies:   RESULT|LENGTH\n<raw bytes>
    //   On error:       ERROR|<reason>\n
    // =========================================================================
    void handleTask(Socket conn) {
        try {
            conn.setSoTimeout(1_800_000); // 30 minute timeout for large files
            conn.setTcpNoDelay(true);

            InputStream  rawIn  = conn.getInputStream();
            OutputStream rawOut = conn.getOutputStream();

            // Read header: TASK|LENGTH
            String header = readLine(rawIn);
            if (header == null) return;

            System.out.println("[" + nodeId + "] Header: " + header);

            if (!header.startsWith("TASK|")) {
                writeLine(rawOut, "ERROR|Expected TASK|LENGTH");
                rawOut.flush();
                return;
            }

            // Parse data length
            long dataLen;
            try {
                dataLen = Long.parseLong(header.split("\\|")[1].trim());
            } catch (NumberFormatException e) {
                writeLine(rawOut, "ERROR|Invalid length in header: " + header);
                rawOut.flush();
                return;
            }

            System.out.println("[" + nodeId + "] Receiving " + formatSize(dataLen) + "...");

            // Read exactly dataLen bytes
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024]; // 64KB chunks
            long remaining = dataLen;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read   = rawIn.read(buf, 0, toRead);
                if (read == -1) throw new EOFException("Stream ended early");
                baos.write(buf, 0, read);
                remaining -= read;
            }

            System.out.println("[" + nodeId + "] Finished receiving all bytes");

            String input = baos.toString("UTF-8");
            System.out.println("[" + nodeId + "] Processing...");

            long startTime = System.currentTimeMillis();
            String result  = processTask(input);
            long elapsed   = System.currentTimeMillis() - startTime;

            System.out.println("[" + nodeId + "] Processed in " + elapsed + "ms");

            // Send back RESULT|LENGTH\n<bytes>
            byte[] resultBytes = result.getBytes("UTF-8");
            writeLine(rawOut, "RESULT|" + resultBytes.length);
            rawOut.write(resultBytes);
            rawOut.flush();

            System.out.println("[" + nodeId + "] Result sent (" + formatSize(resultBytes.length) + ")");

        } catch (EOFException e) {
            System.err.println("[" + nodeId + "] Connection dropped mid-transfer");
        } catch (SocketTimeoutException e) {
            System.err.println("[" + nodeId + "] Socket timed out waiting for data");
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task error: " + e.getMessage());
            // Try to send error back to server
            try {
                writeLine(conn.getOutputStream(), "ERROR|" + e.getMessage());
                conn.getOutputStream().flush();
            } catch (IOException ignored) {}
        } finally {
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    /**
     * Reads a line from a raw InputStream one byte at a time.
     * Returns null if the stream is closed before any data is read.
     */
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

    /**
     * Writes a line to a raw OutputStream followed by a newline character.
     */
    static void writeLine(OutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
    }

    /**
     * Streams exactly length bytes from in to out in 64KB chunks.
     * Never loads the full data into memory.
     */
    static void streamBytes(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long remaining = length;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read   = in.read(buf, 0, toRead);
            if (read == -1) throw new EOFException("Stream ended early");
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    /**
     * Formats a byte count into a human readable string.
     */
    static String formatSize(long b) {
        if (b < 1024)           return b + " B";
        if (b < 1024 * 1024)    return String.format("%.1f KB", b / 1024.0);
        return                         String.format("%.1f MB", b / 1024.0 / 1024.0);
    }
}