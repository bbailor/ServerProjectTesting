import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SERVICE NODE (Abstract Base Class)
 *
 * All service nodes extend this class.
 *
 * Protocol:
 *   Server sends:   TASK|LENGTH\n<raw bytes>
 *   Node replies:   RESULT|LENGTH\n<raw bytes>
 *   On error:       ERROR|<reason>\n
 *
 * IMPORTANT: Uses DataInputStream throughout — never mix with
 * BufferedInputStream or bytes will be silently consumed.
 */
public abstract class ServiceNode {

    static String serverIp;
    static int    serverUdpPort = 9001;
    static int    myTcpPort;
    static String serviceName;
    static String nodeId;

    static final ExecutorService threadPool = Executors.newFixedThreadPool(8);
    static final Random          random     = new Random();

    // =========================================================================
    // INIT
    // =========================================================================
    void init() throws Exception {
        nodeId = "SN-" + serviceName + "-" + myTcpPort;

        System.out.println("===========================================");
        System.out.println("   SERVICE NODE: " + serviceName);
        System.out.println("===========================================");
        System.out.println("[" + nodeId + "] Server:   " + serverIp + ":" + serverUdpPort);
        System.out.println("[" + nodeId + "] TCP Port: " + myTcpPort);
        System.out.println("[" + nodeId + "] Threads:  8");
        System.out.println();

        try (ServerSocket test = new ServerSocket(myTcpPort)) {
            System.out.println("[" + nodeId + "] Port " + myTcpPort + " is available.");
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] ERROR: Port " + myTcpPort + " already in use. Exiting.");
            System.exit(1);
        }

        startHeartbeatSender();
        startTcpListener();
    }

    // =========================================================================
    // ABSTRACT METHODS
    // =========================================================================

    /** Override for text-based services (CSV, HMAC, TOPK) */
    String processTask(String input) throws Exception {
        throw new UnsupportedOperationException("processTask not implemented");
    }

    /** Override for binary services (COMPRESSION, IMAGE) */
    byte[] processBytes(byte[] inputBytes) throws Exception {
        String input  = new String(inputBytes, "UTF-8");
        String result = processTask(input);
        return result.getBytes("UTF-8");
    }

    /** Return true in binary service nodes (COMPRESSION, IMAGE) */
    boolean isBinaryService() { return false; }

    // =========================================================================
    // HEARTBEAT
    // =========================================================================
    void startHeartbeatSender() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatSender");
            t.setDaemon(true);
            return t;
        });
        scheduler.execute(this::sendHeartbeat);
        scheduleNextHeartbeat(scheduler);
    }

    void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
        int delay = 15 + random.nextInt(16);
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
        }
    }

    // =========================================================================
    // TCP LISTENER
    // =========================================================================
    void startTcpListener() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(myTcpPort)) {
            serverSocket.setReceiveBufferSize(1024 * 1024);
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
    // Uses DataInputStream for ALL reads — never BufferedInputStream.
    // This is critical to prevent bytes being silently consumed by a buffer
    // when switching between line reading and byte reading.
    // =========================================================================
    void handleTask(Socket conn) {
        try {
            conn.setSoTimeout(1_800_000);
            conn.setTcpNoDelay(true);

            // IMPORTANT: Use DataInputStream for everything — one instance only
            DataInputStream  rawIn  = new DataInputStream(conn.getInputStream());
            DataOutputStream rawOut = new DataOutputStream(conn.getOutputStream());

            // Read header line: TASK|LENGTH
            String header = readLine(rawIn);
            if (header == null) return;

            System.out.println("[" + nodeId + "] Header: " + header);

            if (!header.startsWith("TASK|")) {
                writeLine(rawOut, "ERROR|Expected TASK|LENGTH");
                return;
            }

            long dataLen;
            try {
                dataLen = Long.parseLong(header.split("\\|")[1].trim());
            } catch (NumberFormatException e) {
                writeLine(rawOut, "ERROR|Invalid length: " + header);
                return;
            }

            System.out.println("[" + nodeId + "] Receiving " + formatSize(dataLen) + "...");
            long receiveStart = System.currentTimeMillis();

            // Read exactly dataLen bytes using readFully — guarantees all bytes arrive
            byte[] inputBytes = new byte[(int) dataLen];
            rawIn.readFully(inputBytes);

            System.out.println("[" + nodeId + "] Received in "
                + (System.currentTimeMillis() - receiveStart) + "ms");
            System.out.println("[" + nodeId + "] Processing...");

            long   processStart = System.currentTimeMillis();
            byte[] resultBytes;

            if (isBinaryService()) {
                resultBytes = processBytes(inputBytes);
            } else {
                String input  = new String(inputBytes, "UTF-8");
                String result = processTask(input);
                resultBytes   = result.getBytes("UTF-8");
            }

            System.out.println("[" + nodeId + "] Processed in "
                + (System.currentTimeMillis() - processStart) + "ms");

            // Send RESULT|LENGTH\n<raw bytes>
            writeLine(rawOut, "RESULT|" + resultBytes.length);
            rawOut.write(resultBytes);
            rawOut.flush();

            System.out.println("[" + nodeId + "] Result sent (" + formatSize(resultBytes.length) + ")");

        } catch (EOFException e) {
            System.err.println("[" + nodeId + "] Connection dropped mid-transfer: " + e.getMessage());
        } catch (SocketTimeoutException e) {
            System.err.println("[" + nodeId + "] Socket timed out");
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task error: "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                writeLine(new DataOutputStream(conn.getOutputStream()), "ERROR|" + e.getMessage());
            } catch (IOException ignored) {}
        } finally {
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    // =========================================================================
    // UTILITIES — all use DataInputStream/DataOutputStream
    // =========================================================================

    /**
     * Read a line from DataInputStream one byte at a time.
     * Strips \r, stops at \n. Returns null if stream closed.
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
     * Write a string followed by \n to a DataOutputStream.
     */
    static void writeLine(DataOutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
        out.flush();
    }

    static String formatSize(long b) {
        if (b < 1024)        return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return                      String.format("%.1f MB", b / 1024.0 / 1024.0);
    }
}