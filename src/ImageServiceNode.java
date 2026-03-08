import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.*;
import java.net.*;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * IMAGE TRANSFORM SERVICE NODE
 *
 * Registration name: IMAGE
 *
 * Supported operations (sent as TASK|<operation>:<base64ImageData>):
 *
 *   GRAYSCALE:<base64>          → converts image to grayscale
 *   ROTATE:<degrees>:<base64>   → rotates image (90, 180, 270)
 *   RESIZE:<width>x<height>:<base64>  → resizes image to given dimensions
 *   THUMBNAIL:<base64>          → resizes image to 128x128 thumbnail
 *
 * The node returns: RESULT|<base64EncodedResultImage>
 * On error:         RESULT|ERROR|<reason>
 *
 * Images are transferred as Base64-encoded PNG over the existing text protocol.
 *
 * Run:
 *   javac ImageServiceNode.java
 *   java ImageServiceNode <serverIp> <myTcpPort>
 *
 * Example:
 *   java ImageServiceNode 127.0.0.1 9102
 */
public class ImageServiceNode {

    static final String SERVICE_NAME = "IMAGE";
    static final int    SERVER_UDP   = 9001;

    static String serverIp;
    static int    myTcpPort;
    static String nodeId;

    static final Random random = new Random();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java ImageServiceNode <serverIp> <myTcpPort>");
            System.out.println("Example: java ImageServiceNode 127.0.0.1 9102");
            System.exit(1);
        }

        serverIp  = args[0];
        myTcpPort = Integer.parseInt(args[1]);
        nodeId    = "SN-" + SERVICE_NAME + "-" + myTcpPort;

        System.out.println("[" + nodeId + "] Starting Image Transform Service Node...");
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

    // UDP Heartbeat Sender
    
    static void startHeartbeatSender() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatSender");
            t.setDaemon(true);
            return t;
        });
        scheduler.execute(ImageServiceNode::sendHeartbeat);
        scheduleNextHeartbeat(scheduler);
    }

    static void scheduleNextHeartbeat(ScheduledExecutorService scheduler) {
        int delay = 15 + random.nextInt(16);
        scheduler.schedule(() -> {
            sendHeartbeat();
            scheduleNextHeartbeat(scheduler);
        }, delay, TimeUnit.SECONDS);
    }

    static void sendHeartbeat() {
        try (DatagramSocket udp = new DatagramSocket()) {
            String msg  = "HEARTBEAT|" + nodeId + "|" + SERVICE_NAME + "|" + myTcpPort;
            byte[] data = msg.getBytes("UTF-8");
            InetAddress addr = InetAddress.getByName(serverIp);
            udp.send(new DatagramPacket(data, data.length, addr, SERVER_UDP));
            System.out.println("[" + nodeId + "] Heartbeat sent: " + msg);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Heartbeat error: " + e.getMessage());
        }
    }

    // TCP Task Listener
    // Images can be large so we use a generous socket buffer and line length

    static void startTcpListener() throws Exception {
        try (ServerSocket ss = new ServerSocket(myTcpPort)) {
            // Increase receive buffer for large base64 image data
            ss.setReceiveBufferSize(1024 * 1024); // 1 MB
            System.out.println("[" + nodeId + "] Listening for tasks on TCP port " + myTcpPort + "...");
            while (true) {
                Socket conn = ss.accept();
                conn.setReceiveBufferSize(1024 * 1024);
                conn.setSendBufferSize(1024 * 1024);
                System.out.println("[" + nodeId + "] Connection from: " + conn.getInetAddress().getHostAddress());
                Thread t = new Thread(() -> handleTask(conn), "TaskHandler");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    static void handleTask(Socket conn) {
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream(),  "UTF-8"), 1024 * 1024);
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"), true)
        ) {
            String line = in.readLine();
            if (line == null) return;

            // Don't log the full line — it could be megabytes of base64
            System.out.println("[" + nodeId + "] Task received (length=" + line.length() + ")");

            if (!line.startsWith("TASK|")) {
                out.println("RESULT|ERROR|Expected format: TASK|<operation>:<base64image>");
                return;
            }

            String input  = line.substring(5).trim();
            String result = processImage(input);

            out.println("RESULT|" + result);
            System.out.println("[" + nodeId + "] Result sent (length=" + result.length() + ")");

        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task handling error: " + e.getMessage());
        } finally {
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    // Core image processing logic
    //
    // All operations receive and return images as Base64-encoded PNG strings.

    static String processImage(String input) {
        try {
            String upper = input.toUpperCase();

            if (upper.startsWith("GRAYSCALE:")) {
                String b64 = input.substring("GRAYSCALE:".length()).trim();
                BufferedImage img = decodeImage(b64);
                BufferedImage result = toGrayscale(img);
                System.out.println("[" + nodeId + "] Grayscale applied to " + img.getWidth() + "x" + img.getHeight() + " image");
                return encodeImage(result);

            } else if (upper.startsWith("THUMBNAIL:")) {
                String b64 = input.substring("THUMBNAIL:".length()).trim();
                BufferedImage img = decodeImage(b64);
                BufferedImage result = resize(img, 128, 128);
                System.out.println("[" + nodeId + "] Thumbnail created from " + img.getWidth() + "x" + img.getHeight());
                return encodeImage(result);

            } else if (upper.startsWith("ROTATE:")) {
                // Format: ROTATE:<degrees>:<base64>
                String rest = input.substring("ROTATE:".length());
                int colon = rest.indexOf(':');
                if (colon < 0) return "ERROR|Format: ROTATE:<degrees>:<base64image>";

                int    degrees = Integer.parseInt(rest.substring(0, colon).trim());
                String b64     = rest.substring(colon + 1).trim();

                BufferedImage img    = decodeImage(b64);
                BufferedImage result = rotate(img, degrees);
                System.out.println("[" + nodeId + "] Rotated " + degrees + "° on " + img.getWidth() + "x" + img.getHeight() + " image");
                return encodeImage(result);

            } else if (upper.startsWith("RESIZE:")) {
                // Format: RESIZE:<width>x<height>:<base64>
                String rest = input.substring("RESIZE:".length());
                int colon = rest.indexOf(':');
                if (colon < 0) return "ERROR|Format: RESIZE:<width>x<height>:<base64image>";

                String dims = rest.substring(0, colon).trim();
                String b64  = rest.substring(colon + 1).trim();

                String[] wh = dims.toLowerCase().split("x");
                if (wh.length != 2) return "ERROR|Dimensions must be like 800x600";

                int w = Integer.parseInt(wh[0].trim());
                int h = Integer.parseInt(wh[1].trim());

                BufferedImage img    = decodeImage(b64);
                BufferedImage result = resize(img, w, h);
                System.out.println("[" + nodeId + "] Resized " + img.getWidth() + "x" + img.getHeight() + " -> " + w + "x" + h);
                return encodeImage(result);

            } else {
                return "ERROR|Unknown operation. Use GRAYSCALE, THUMBNAIL, ROTATE:<deg>, or RESIZE:<w>x<h>";
            }

        } catch (NumberFormatException e) {
            return "ERROR|Invalid number in parameters: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "ERROR|Invalid image data: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR|Processing failed: " + e.getMessage();
        }
    }

    // Image helpers

    /** Decode a Base64 string into a BufferedImage */
    static BufferedImage decodeImage(String b64) throws Exception {
        b64 = b64.replaceAll("\\s", ""); // strip any whitespace
        byte[] bytes = Base64.getDecoder().decode(b64);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        BufferedImage img = ImageIO.read(bais);
        if (img == null) throw new IllegalArgumentException("Could not decode image — is it a valid PNG/JPG?");
        return img;
    }

    /** Encode a BufferedImage to a Base64 PNG string */
    static String encodeImage(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** Convert image to grayscale */
    static BufferedImage toGrayscale(BufferedImage original) {
        BufferedImage gray = new BufferedImage(
            original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY
        );
        Graphics2D g = gray.createGraphics();
        g.drawImage(original, 0, 0, null);
        g.dispose();
        return gray;
    }

    /** Resize image to given width x height */
    static BufferedImage resize(BufferedImage original, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        // Use high-quality rendering hints
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,      RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    /** Rotate image by degrees (supports any angle, best with 90/180/270) */
    static BufferedImage rotate(BufferedImage original, int degrees) {
        double radians = Math.toRadians(degrees);
        double sin     = Math.abs(Math.sin(radians));
        double cos     = Math.abs(Math.cos(radians));

        int origW = original.getWidth();
        int origH = original.getHeight();

        // Calculate new canvas size so the rotated image fits without clipping
        int newW = (int) Math.floor(origW * cos + origH * sin);
        int newH = (int) Math.floor(origH * cos + origW * sin);

        BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Translate to center, rotate, translate back
        AffineTransform at = new AffineTransform();
        at.translate((newW - origW) / 2.0, (newH - origH) / 2.0);
        at.rotate(radians, origW / 2.0, origH / 2.0);
        g.drawImage(original, at, null);
        g.dispose();
        return rotated;
    }
}
