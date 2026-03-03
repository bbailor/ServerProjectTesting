import java.io.*;
import java.net.*;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * CSV SERVICE NODE
 *
 * Takes a string input and returns a GZIP-compressed version of it, encoded in Base64.
 * 
 */

public class CSVServiceNode {

    static String serverIp;
    static int    serverUdpPort = 9001;   // must match Server.UDP_HB_PORT
    static int    myTcpPort;
    static String serviceName;
    static String nodeId;

    static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java ServiceNode <serverIp> <myTcpPort> <serviceName>");
            System.out.println("Example: java ServiceNode 54.123.45.67 9100 BASE64");
            System.exit(1);
        }

        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = args[2].toUpperCase();
        nodeId      = "SN-" + serviceName + "-" + myTcpPort;

        System.out.println("[" + nodeId + "] Starting...");
        System.out.println("[" + nodeId + "] Will send heartbeats to " + serverIp + ":" + serverUdpPort);
        System.out.println("[" + nodeId + "] Listening for tasks on TCP port " + myTcpPort);

        // Start heartbeat sender in background
        startHeartbeatSender();

        // Start TCP listener — blocks here accepting task requests from server
        startTcpListener();
    }

    // -------------------------------------------------------------------------
    // UDP Heartbeat Sender
    // Sends a heartbeat to the server every 15-30 seconds (random interval)
    // Format: HEARTBEAT|<nodeId>|<serviceName>|<tcpPort>
    // -------------------------------------------------------------------------
    static void startHeartbeatSender() {
        // We use a scheduled executor so heartbeat timing is reliable
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // Send first heartbeat immediately so server knows we're up right away
        scheduler.execute(() -> sendHeartbeat());

        // Then schedule repeating heartbeats at random intervals (15-30s)
        // We reschedule after each one to get a fresh random delay
        scheduleNextHeartbeat(scheduler);
    }

    static void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
        int delaySeconds = 15 + random.nextInt(16); // random between 15 and 30
        scheduler.schedule(() -> {
            sendHeartbeat();
            scheduleNextHeartbeat(scheduler); // reschedule with new random delay
        }, delaySeconds, TimeUnit.SECONDS);
    }

    static void sendHeartbeat() {
        try (DatagramSocket udpSocket = new DatagramSocket()) {
            // Format: HEARTBEAT|SN-BASE64-9100|BASE64|9100
            String msg = "HEARTBEAT|" + nodeId + "|" + serviceName + "|" + myTcpPort;
            byte[] data = msg.getBytes();

            InetAddress serverAddr = InetAddress.getByName(serverIp);
            DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverUdpPort);
            udpSocket.send(packet);

            System.out.println("[" + nodeId + "] Heartbeat sent: " + msg);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Heartbeat failed: " + e.getMessage());
            // Don't crash — just log it. Will retry on next scheduled heartbeat.
        }
    }

    // -------------------------------------------------------------------------
    // TCP Task Listener
    // Accepts connections from the server's client-threads.
    // Each connection gets its own thread so multiple tasks can run in parallel.
    //
    // Protocol:
    //   Server sends:   TASK|<input>
    //   Node replies:   RESULT|<output>   or   ERROR|<reason>
    // -------------------------------------------------------------------------
    static void startTcpListener() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(myTcpPort)) {
            System.out.println("[" + nodeId + "] TCP ready, waiting for tasks...");
            while (true) {
                Socket conn = serverSocket.accept();
                System.out.println("[" + nodeId + "] Task connection from: " + conn.getInetAddress());

                // Handle each task in its own thread so we don't block
                Thread t = new Thread(() -> handleTask(conn), "TaskHandler");
                t.start();
            }
        }
    }

    static void handleTask(Socket conn) {
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            PrintWriter    out = new PrintWriter(conn.getOutputStream(), true)
        ) {
            String line = in.readLine();
            if (line == null) return;

            System.out.println("[" + nodeId + "] Task received: " + line);

            // Parse:  TASK|<input>
            if (!line.startsWith("TASK|")) {
                out.println("ERROR|Expected TASK|<input>");
                return;
            }

            String input = line.substring(5); // everything after "TASK|"

            // Process the task using whichever service this node runs
            String result = processTask(input);

            out.println("RESULT|" + result);
            System.out.println("[" + nodeId + "] Result sent: " + result);

        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task error: " + e.getMessage());
        } finally {
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // The actual service logic lives here.
    // In your real project, each ServiceNode file would have its own version
    // of this method implementing its specific service.
    //
    // This demo switches on serviceName so you can test multiple services
    // with the same file — in the real project, split into separate files.
    // -------------------------------------------------------------------------
    static String processTask(String input) {
        try {
            String[] values = input.split(",");
            double[] nums = new double[values.length];

            for (int i = 0; i < values.length; i++){
                nums[i] = Double.parseDouble(values[i].trim());
            }

            // calculate mean
            double sum = 0;
            for (double n : nums){
                sum += n;
            }
            double mean = sum/nums.length;

            //calculate min and max
            double min = nums[0];
            double max = nums[0];

            for (double n : nums){
                if(n < min){
                    min = n;
                }
                if (n > max){
                    max = n;
                }
            }

            //calculate standard deviation
            double variance = 0;
            for (double n : nums){
                variance += Math.pow(n - mean, 2);
            }
            double std = Math.sqrt(variance/nums.length);

            //calculate median
            double[] sorted = nums.clone();
            java.util.Arrays.sort(sorted);
            double median;
            if (sorted.length % 2 == 0){
                median = (sorted[sorted.length/2-1] + sorted[sorted.length/2])/2;
            } else {
                median = sorted[sorted.length/2];
            }

            return String.format("mean=%.2f, median=%.2f, std=%.2f, min=%.2f, max=%.2f", mean, median, std, min, max);

        } catch (Exception e) {
            return "ERROR|Processing failed: " + e.getMessage();
        }
    }
}