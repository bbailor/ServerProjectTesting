// import java.io.*;
// import java.net.*;
// import java.util.Base64;
// import java.util.Random;
// import java.util.concurrent.ExecutorService;
// import java.util.concurrent.Executors;
// import java.util.concurrent.ScheduledExecutorService;
// import java.util.concurrent.TimeUnit;
// import java.util.zip.GZIPInputStream;
// import java.util.zip.GZIPOutputStream;

// /**
//  * COMPRESSION SERVICE NODE
//  *
//  * Takes a string input and returns a GZIP-compressed version of it, encoded in Base64.
//  * 
//  */

// public class CompressionServiceNode {

//     static String serverIp;
//     static int    serverUdpPort = 9001;   // must match Server.UDP_HB_PORT
//     static int    myTcpPort;
//     static String serviceName;
//     static String nodeId;

//     //max threads open to stop crashes
//     static final ExecutorService threadPool = Executors.newFixedThreadPool(10);

//     static final Random random = new Random();

//     public static void main(String[] args) throws Exception {
//         if (args.length != 2) {
//             System.out.println("Usage: java ServiceNode <serverIp> <myTcpPort>");
//             System.out.println("Example: java ServiceNode 54.123.45.67 9100");
//             System.exit(1);
//         }

//         serverIp    = args[0];
//         myTcpPort   = Integer.parseInt(args[1]);
//         serviceName = "COMPRESSION";
//         nodeId      = "SN-" + serviceName + "-" + myTcpPort;

//         System.out.println("[" + nodeId + "] Starting...");
//         System.out.println("[" + nodeId + "] Will send heartbeats to " + serverIp + ":" + serverUdpPort);
//         System.out.println("[" + nodeId + "] Listening for tasks on TCP port " + myTcpPort);

//         // Test if port is available before doing anything
//         try (ServerSocket test = new ServerSocket(myTcpPort)) {
//             // Port is free, close the test socket and proceed normally
//         } catch (IOException e) {
//             System.err.println("[" + nodeId + "] ERROR: Port " + myTcpPort + " is already in use. Exiting.");
//             System.exit(1);  // kills everything including the heartbeat thread
//         }

//         //start after confirming port is available
//         startHeartbeatSender();
//         startTcpListener();
//     }

//     // -------------------------------------------------------------------------
//     // UDP Heartbeat Sender
//     // Sends a heartbeat to the server every 15-30 seconds (random interval)
//     // Format: HEARTBEAT|<nodeId>|<serviceName>|<tcpPort>
//     // -------------------------------------------------------------------------
//     static void startHeartbeatSender() {
//         // We use a scheduled executor so heartbeat timing is reliable
//         ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

//         // Send first heartbeat immediately so server knows we're up right away
//         scheduler.execute(() -> sendHeartbeat());

//         // Then schedule repeating heartbeats at random intervals (15-30s)
//         // We reschedule after each one to get a fresh random delay
//         scheduleNextHeartbeat(scheduler);
//     }

//     static void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
//         int delaySeconds = 15 + random.nextInt(16); // random between 15 and 30
//         scheduler.schedule(() -> {
//             sendHeartbeat();
//             scheduleNextHeartbeat(scheduler); // reschedule with new random delay
//         }, delaySeconds, TimeUnit.SECONDS);
//     }

//     static void sendHeartbeat() {
//         try (DatagramSocket udpSocket = new DatagramSocket()) {
//             // Format: HEARTBEAT|SN-BASE64-9100|BASE64|9100
//             String msg = "HEARTBEAT|" + nodeId + "|" + serviceName + "|" + myTcpPort;
//             byte[] data = msg.getBytes();

//             InetAddress serverAddr = InetAddress.getByName(serverIp);
//             DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverUdpPort);
//             udpSocket.send(packet);

//             System.out.println("[" + nodeId + "] Heartbeat sent: " + msg);
//         } catch (Exception e) {
//             System.err.println("[" + nodeId + "] Heartbeat failed: " + e.getMessage());
//             // Don't crash — just log it. Will retry on next scheduled heartbeat.
//         }
//     }

//     // -------------------------------------------------------------------------
//     // TCP Task Listener
//     // Accepts connections from the server's client-threads.
//     // Each connection gets its own thread so multiple tasks can run in parallel.
//     //
//     // Protocol:
//     //   Server sends:   TASK|<input>
//     //   Node replies:   RESULT|<output>   or   ERROR|<reason>
//     // -------------------------------------------------------------------------
//     static void startTcpListener() throws Exception {
//         try (ServerSocket serverSocket = new ServerSocket(myTcpPort)) {
//             System.out.println("[" + nodeId + "] TCP ready, waiting for tasks...");
//             while (true) {
//                 Socket conn = serverSocket.accept();
//                 System.out.println("[" + nodeId + "] Task connection from: " + conn.getInetAddress());

//                 // Handle each task in its own thread so we don't block
//                 threadPool.submit(() -> handleTask(conn));
//             }
//         }
//     }

// static void handleTask(Socket conn) {
//     try {
//         conn.setSoTimeout(600_000);
//         conn.setTcpNoDelay(true);

//         InputStream  rawIn  = conn.getInputStream();
//         OutputStream rawOut = conn.getOutputStream();

//         // Read header line: TASK|LENGTH
//         String header = readLine(rawIn);
//         if (header == null) return;

//         System.out.println("[" + nodeId + "] Task received: " + header.substring(0, Math.min(header.length(), 50)));

//         if (!header.startsWith("TASK|")) {
//             writeLine(rawOut, "ERROR|Expected TASK|LENGTH");
//             rawOut.flush();
//             return;
//         }

//         // Parse the length
//         long dataLen = Long.parseLong(header.split("\\|")[1].trim());
//         System.out.println("[" + nodeId + "] Receiving " + dataLen + " bytes");

//         // Read exactly dataLen bytes from the stream
//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         byte[] buf = new byte[64 * 1024];
//         long remaining = dataLen;
//         while (remaining > 0) {
//             int toRead = (int) Math.min(buf.length, remaining);
//             int read = rawIn.read(buf, 0, toRead);
//             if (read == -1) throw new EOFException("Stream ended early");
//             baos.write(buf, 0, read);
//             remaining -= read;
//         }

//         String input = baos.toString("UTF-8");
//         System.out.println("[" + nodeId + "] Processing task...");







//         System.out.println("[" + nodeId + "] Starting compression at " + System.currentTimeMillis());
//         String result = processTask(input);
//         System.out.println("[" + nodeId + "] Compression finished at " + System.currentTimeMillis());






//         // Process the task
//         // result = processTask(input);

//         // Send back RESULT|LENGTH\n<bytes>
//         byte[] resultBytes = result.getBytes("UTF-8");
//         writeLine(rawOut, "RESULT|" + resultBytes.length);
//         rawOut.write(resultBytes);
//         rawOut.flush();

//         System.out.println("[" + nodeId + "] Result sent (" + resultBytes.length + " bytes)");

//     } catch (Exception e) {
//         System.err.println("[" + nodeId + "] Task error: " + e.getMessage());
//     } finally {
//         try { conn.close(); } catch (IOException ignored) {}
//     }
// }

//     // -------------------------------------------------------------------------
//     // Process Compression/Decompression Requests
//     // 
//     // Input Formats:
//     //      - "DECOMPRESS|<base64-compressed-data>" -> decompress and return to original
//     //      - "FILE:<base64-file-bytes>" -> compress file bytes, return Base64
//     //      - "<plain-text>" -> compress text, return Base64
//     // -------------------------------------------------------------------------
//     static String processTask(String input) {
//         try {
//             //check if it's a decompression request
//             if (input.startsWith("DECOMPRESS|")) {
//                 String base64input = input.substring(11);
//                 return decompress(base64input);
//             }
            
//             // Check if it's a file (binary data encoded as Base64)
//             if (input.startsWith("FILE:")) {
//                 String base64FileData = input.substring(5);
//                 // Decode the file bytes, compress them, return as Base64
//                 byte[] fileBytes = Base64.getDecoder().decode(base64FileData);
//                 return compressBytes(fileBytes);
//             }
            
//             // Otherwise, treat as plain text to compress
//             return compress(input);

//         } catch (Exception e) {
//             return "ERROR|Processing failed: " + e.getMessage();
//         }
//     }

//     // compress' string input as base64
//     static String compress(String input) throws Exception {
//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         GZIPOutputStream gzip = new GZIPOutputStream(baos);
//         gzip.write(input.getBytes("UTF-8"));
//         gzip.close();
//         return Base64.getEncoder().encodeToString(baos.toByteArray());
//     }

//     // Compress raw bytes, return Base64-encoded compressed data
//     // Prefixes with "FILE:" so decompression knows to return file bytes
//     static String compressBytes(byte[] input) throws Exception {
//         ByteArrayOutputStream baos = new ByteArrayOutputStream();
//         try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
//             // Write a marker so we know this was file data
//             gzip.write("FILE:".getBytes("UTF-8"));
//             gzip.write(Base64.getEncoder().encode(input));
//         }
//         return Base64.getEncoder().encodeToString(baos.toByteArray());
//     }

//     // decompress' base64 input to string
//     static String decompress(String base64Input) throws Exception {
//         byte[] compressed = Base64.getDecoder().decode(base64Input);
//         ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
//         GZIPInputStream gzip = new GZIPInputStream(bais);
//         ByteArrayOutputStream baos = new ByteArrayOutputStream();

//         byte[] buffer = new byte[64 * 1024];
//         int len;
//         while ((len = gzip.read(buffer)) != -1) {
//             baos.write(buffer, 0, len);
//         }

//         return baos.toString("UTF-8");
//     }

//     static String readLine(InputStream in) throws IOException {
//     ByteArrayOutputStream line = new ByteArrayOutputStream();
//     int b;
//     while ((b = in.read()) != -1) {
//         if (b == '\n') break;
//         if (b != '\r') line.write(b);
//     }
//     if (b == -1 && line.size() == 0) return null;
//     return line.toString("UTF-8");
// }

//     static void writeLine(OutputStream out, String s) throws IOException {
//         out.write((s + "\n").getBytes("UTF-8"));
//     }
// }











import java.io.*;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * COMPRESSION SERVICE NODE
 *
 * Compresses or decompresses input using GZIP.
 *
 * Input formats:
 *   DECOMPRESS|<base64-compressed-data>  → decompresses and returns original
 *   FILE:<base64-file-bytes>             → compresses file bytes, returns Base64
 *   <plain-text>                         → compresses text, returns Base64
 *
 * Run:
 *   javac BaseServiceNode.java CompressionServiceNode.java
 *   java CompressionServiceNode <serverIp> <myTcpPort>
 *
 * Example:
 *   java -Xmx1024m CompressionServiceNode 100.x.x.x 9101
 */
public class CompressionServiceNode extends ServiceNode {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java CompressionServiceNode <serverIp> <myTcpPort>");
            System.out.println("Example: java CompressionServiceNode 100.x.x.x 9101");
            System.exit(1);
        }

        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "COMPRESSION";

        new CompressionServiceNode().init();
    }

    @Override
    String processTask(String input) throws Exception {
        // Decompression request
        if (input.startsWith("DECOMPRESS|")) {
            String base64Input = input.substring(11);
            return decompress(base64Input);
        }

        // File compression request
        if (input.startsWith("FILE:")) {
            String base64FileData = input.substring(5);
            byte[] fileBytes = Base64.getDecoder().decode(base64FileData);
            return compressBytes(fileBytes);
        }

        // Plain text compression
        return compress(input);
    }

    // Compress a plain text string and return Base64-encoded result
    static String compress(String input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(input.getBytes("UTF-8"));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // Compress raw bytes and return Base64-encoded result
    // Prefixes compressed data with FILE: so decompression knows to return file bytes
    static String compressBytes(byte[] input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write("FILE:".getBytes("UTF-8"));
            gzip.write(Base64.getEncoder().encode(input));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    // Decompress a Base64-encoded GZIP string and return the original content
    static String decompress(String base64Input) throws Exception {
        byte[] compressed = Base64.getDecoder().decode(base64Input);
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (GZIPInputStream gzip = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[64 * 1024]; // 64KB chunks
            int len;
            while ((len = gzip.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }

        return baos.toString("UTF-8");
    }
}