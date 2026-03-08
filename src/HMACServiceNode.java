import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC SERVICE NODE
 *
 * Registration name: HMAC
 *
 * Supported operations (sent as TASK|<operation>|<key>|<data>):
 *
 *   SIGN|<key>|<message>
 *       → Computes HMAC-SHA256 of <message> using <key>
 *       → Returns Base64-encoded HMAC signature
 *
 *   VERIFY|<key>|<message>|<signature>
 *       → Recomputes HMAC-SHA256 and compares to provided <signature>
 *       → Returns "VALID" or "INVALID"
 *
 * The node returns: RESULT|<output>
 * On error:         RESULT|ERROR|<reason>
 *
 * Run:
 *   javac HMACServiceNode.java
 *   java HMACServiceNode <serverIp> <myTcpPort>
 *
 * Example:
 *   java HMACServiceNode 127.0.0.1 9103
 *
 * Client usage examples:
 *   Service: HMAC
 *   Input:   SIGN|mysecretkey|Hello World
 *   Input:   VERIFY|mysecretkey|Hello World|<base64signature>
 */
public class HMACServiceNode {

    static final String SERVICE_NAME = "HMAC";
    static final int SERVER_UDP = 9001; // must match Server.UDP_HB_PORT
    static final String HMAC_ALGO = "HmacSHA256";

    static String serverIp;
    static int myTcpPort;
    static String nodeId;

    static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java HMACServiceNode <serverIp> <myTcpPort>");
            System.out.println("Example: java HMACServiceNode 127.0.0.1 9103");

            System.exit(1);
        }

        serverIp = args[0];
        myTcpPort = Integer.parseInt(args[1]);
        nodeId = "SN-" + SERVICE_NAME + "-" + myTcpPort;

        System.out.println("[" + nodeId + "] Starting HMAC Signing/Verification Service Node...");
        System.out.println("[" + nodeId + "] Server: " + serverIp + ":" + SERVER_UDP);
        System.out.println("[" + nodeId + "] TCP task port: " + myTcpPort);

        // Test if port is available before doing anything
        try (ServerSocket test = new ServerSocket(myTcpPort)) {
            // Port is free, close the test socket and proceed normally
        } catch (IOException e) {
            System.err.println("[" + nodeId + "] ERROR: Port " + myTcpPort + " is already in use. Exiting.");
            System.exit(1);  // kills everything including the heartbeat thread
        }

        //start after confirming port is available
        startHeartbeatSender();
        startTcpListener();
    }

    // -------------------------------------------------------------------------
    // UDP Heartbeat Sender
    // Sends a heartbeat to the server every 15-30 seconds (random interval)
    // Format: HEARTBEAT|<nodeId>|<serviceName>|<tcpPort>
    // -------------------------------------------------------------------------
    static void startHeartbeatSender() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatSender");
            t.setDaemon(true);
            return t;
        });
        scheduler.execute(HMACServiceNode::sendHeartbeat);
        scheduleNextHeartbeat(scheduler);
    }

    static void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
        int delay = 15 + random.nextInt(16); // random between 15 and 30 seconds
        scheduler.schedule(() -> {
            sendHeartbeat();
            scheduleNextHeartbeat(scheduler);
        }, delay, TimeUnit.SECONDS);
    }

    static void sendHeartbeat() {
        try (DatagramSocket udp = new DatagramSocket()) {
            String msg = "HEARTBEAT|" + nodeId + "|" + SERVICE_NAME + "|" + myTcpPort;
            byte[] data = msg.getBytes("UTF-8");
            InetAddress addr = InetAddress.getByName(serverIp);
            udp.send(new DatagramPacket(data, data.length, addr, SERVER_UDP));
            System.out.println("[" + nodeId + "] Heartbeat sent: " + msg);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Heartbeat error: " + e.getMessage());
            // Don't crash — will retry on next scheduled heartbeat
        }
    }

    // -------------------------------------------------------------------------
    // TCP Task Listener
    // Accepts connections from the server's client-threads.
    // Each connection gets its own thread so multiple tasks can run in parallel.
    //
    // Protocol:
    //   Server sends:   TASK|<input>
    //   Node replies:   RESULT|<output>   or   RESULT|ERROR|<reason>
    // -------------------------------------------------------------------------
    static void startTcpListener() throws Exception {
        try (ServerSocket ss = new ServerSocket(myTcpPort)) {
            System.out.println("[" + nodeId + "] Listening for tasks on TCP port " + myTcpPort + "...");
            while (true) {
                Socket conn = ss.accept();
                System.out.println("[" + nodeId + "] Connection from: " + conn.getInetAddress().getHostAddress());
                Thread t = new Thread(() -> handleTask(conn), "TaskHandler");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    static void handleTask(Socket conn) {
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"), true)) {
           
            System.out.println("Input format: SIGN|KEY|STRING");
            System.out.println("-or\t\tVERIFY|KEY|STRING|<paste signature here>");


            String line = in.readLine();
            if (line == null)
                return;

            System.out.println("[" + nodeId + "] Task received: " + line);

            if (!line.startsWith("TASK|")) {
                out.println("RESULT|ERROR|Expected format: TASK|<operation>|<key>|<data>");
                return;
            }

            String input = line.substring(5).trim(); // everything after "TASK|"
            String result = processTask(input);

            out.println("RESULT|" + result);
            System.out.println("[" + nodeId + "] Result sent: " + result);

        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task handling error: " + e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (IOException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // HMAC Service Logic
    //
    // Input formats:
    //   SIGN|<key>|<message>
    //       Computes HMAC-SHA256 of <message> using <key>.
    //       Returns the Base64-encoded signature.
    //
    //   VERIFY|<key>|<message>|<base64signature>
    //       Recomputes the HMAC and does a constant-time comparison.
    //       Returns "VALID" if signatures match, "INVALID" otherwise.
    //
    // Notes:
    //   - Keys and messages are treated as UTF-8 strings.
    //   - Signatures are Base64-encoded to keep them safe over the text protocol.
    //   - Comparison uses MessageDigest.isEqual for constant-time safety
    //     (avoids timing attacks from short-circuit string comparison).
    // -------------------------------------------------------------------------
    static String processTask(String input) {
        try {
            // Split on '|' but allow the message field to contain '|' characters
            // by limiting splits appropriately per operation
            if (input.toUpperCase().startsWith("SIGN|")) {
                // SIGN|<key>|<message>  — message may contain '|', so split into 3
                String body = input.substring(5); // strip "SIGN|"
                int sep = body.indexOf('|');
                if (sep < 0)
                    return "ERROR|Format: SIGN|<key>|<message>";

                String key = body.substring(0, sep);
                String message = body.substring(sep + 1);

                if (key.isEmpty())
                    return "ERROR|Key cannot be empty";
                if (message.isEmpty())
                    return "ERROR|Message cannot be empty";

                // String signature = computeHmac(key, message);
                byte[] hmacBytes = computeHmacBytes(key, message);

                System.out.println("[" + nodeId + "] Signed message (key=" + key + ")");

                return Base64.getEncoder().encodeToString(hmacBytes);
                // return signature;

            } else if (input.toUpperCase().startsWith("VERIFY|")) {
                // VERIFY|<key>|<message>|<signature>
                // Message may contain '|', so we split from the right for the signature
                String body = input.substring(7); // strip "VERIFY|"

                // Find first '|' for key
                int firstSep = body.indexOf('|');
                if (firstSep < 0)
                    return "ERROR|Format: VERIFY|<key>|<message>|<signature>";

                String key = body.substring(0, firstSep);
                String rest = body.substring(firstSep + 1);

                // Find last '|' for the signature (so the message can contain '|')
                int lastSep = rest.lastIndexOf('|');
                if (lastSep < 0)
                    return "ERROR|Format: VERIFY|<key>|<message>|<signature>";

                String message = rest.substring(0, lastSep);
                String signature = rest.substring(lastSep + 1);

                if (key.isEmpty())
                    return "ERROR|Key cannot be empty";
                if (message.isEmpty())
                    return "ERROR|Message cannot be empty";
                if (signature.isEmpty())
                    return "ERROR|Signature cannot be empty";

                // Constant-time comparison to prevent timing attacks

                // old method

                // byte[] expectedBytes = Base64.getDecoder().decode(expected);
                // byte[] providedBytes = Base64.getDecoder().decode(signature);

                // boolean valid = java.security.MessageDigest.isEqual(
                //     expectedBytes,
                //     providedBytes
                // );

                // new method
                byte[] expected = computeHmacBytes(key, message);
                byte[] provided = Base64.getDecoder().decode(signature);

                boolean valid = java.security.MessageDigest.isEqual(expected, provided);

                System.out.println(
                        "[" + nodeId + "] Verified message (key=" + key + "): " + (valid ? "VALID" : "INVALID"));
                return valid ? "VALID" : "INVALID";

            } else {
                return "ERROR|Unknown operation. Use SIGN|<key>|<message> or VERIFY|<key>|<message>|<signature>";
            }

        } catch (Exception e) {
            return "ERROR|Processing failed: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // Compute HMAC-SHA256 and return result as a Base64-encoded string
    // -------------------------------------------------------------------------

    //
    // OLD METHOD (less efficient)
    //
    // static String computeHmac(String key, String message) throws Exception {
    //     Mac mac = Mac.getInstance(HMAC_ALGO);
    //     SecretKeySpec keySpec = new SecretKeySpec(
    //             key.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
    //     mac.init(keySpec);
    //     byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    //     return Base64.getEncoder().encodeToString(hmacBytes);
    // }    

    static byte[] computeHmacBytes(String key, String message) throws Exception {

        Mac mac = Mac.getInstance("HmacSHA256");

        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");

        mac.init(keySpec);

        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}