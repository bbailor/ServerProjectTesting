import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;

/**
 * CLIENT
 *
 * Run:
 *   javac Client.java
 *   java Client <SERVER_IP>
 */
public class Client {

    static final int SERVER_TCP_PORT = 9000;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Client <serverIp>");
            System.exit(1);
        }

        String serverIp = args[0];
        Scanner scanner = new Scanner(System.in);

        System.out.println("[Client] Connecting to server at " + serverIp + ":" + SERVER_TCP_PORT);

        try (Socket socket = new Socket(serverIp, SERVER_TCP_PORT)) {
            socket.setSoTimeout(1_800_000);
            socket.setTcpNoDelay(true);
            socket.setReceiveBufferSize(1024 * 1024);
            socket.setSendBufferSize(1024 * 1024);

            // IMPORTANT: one DataInputStream and one DataOutputStream for ALL I/O
            // Never mix with BufferedInputStream/BufferedReader or bytes get lost
            DataInputStream  rawIn  = new DataInputStream(socket.getInputStream());
            DataOutputStream rawOut = new DataOutputStream(socket.getOutputStream());

            System.out.println("[Client] Connected!\n");

            while (true) {
                System.out.println("=== MENU ===");
                System.out.println("1. List available services");
                System.out.println("2. Request a service");
                System.out.println("3. Exit");
                System.out.print("Choice: ");

                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1":
                        listServices(rawOut, rawIn);
                        break;
                    case "2":
                        requestService(rawOut, rawIn, scanner);
                        break;
                    case "3":
                        System.out.println("[Client] Disconnecting.");
                        return;
                    default:
                        System.out.println("Invalid choice.\n");
                }
            }
        } catch (ConnectException e) {
            System.err.println("[Client] Could not connect to " + serverIp + ":" + SERVER_TCP_PORT);
            System.err.println("         Is the server running?");
        }
    }

    // -------------------------------------------------------------------------
    // List services
    // -------------------------------------------------------------------------
    static void listServices(DataOutputStream rawOut, DataInputStream rawIn) throws IOException {
        System.out.println("\n[Client] Requesting service list...");
        writeLine(rawOut, "LIST");

        String response = readLine(rawIn);
        System.out.println("[Client] Server responded: " + response);

        if (response != null && response.startsWith("SERVICES|")) {
            System.out.println("\nAvailable services:");
            for (String s : response.substring(9).split(","))
                System.out.println("  - " + s.trim());
        } else {
            System.out.println("  " + response);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Route service requests
    // -------------------------------------------------------------------------
    static void requestService(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.print("\nEnter service name: ");
        String service = scanner.nextLine().trim().toUpperCase();

        if (service.isEmpty()) {
            System.out.println("Service name cannot be empty.\n");
            return;
        }

        // Check availability
        writeLine(rawOut, "LIST");
        String listResponse = readLine(rawIn);
        if (listResponse == null || !listResponse.startsWith("SERVICES|")) {
            System.out.println(">>> Error: Could not retrieve service list.\n");
            return;
        }
        boolean found = false;
        for (String s : listResponse.substring(9).split(",")) {
            if (s.trim().equalsIgnoreCase(service)) { found = true; break; }
        }
        if (!found) {
            System.out.println(">>> Error: Service '" + service + "' is not currently available.");
            System.out.println(">>> Type 1 from the menu to see available services.\n");
            return;
        }

        switch (service) {
            case "COMPRESSION": handleCompressionService(rawOut, rawIn, scanner); break;
            case "IMAGE":       handleImageService(rawOut, rawIn, scanner);       break;
            default:            handleTextService(service, rawOut, rawIn, scanner); break;
        }
    }

    // -------------------------------------------------------------------------
    // Generic text service (CSV, HMAC, TOPK)
    // -------------------------------------------------------------------------
    static void handleTextService(String service, DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        if (service.equals("HMAC")) {
            System.out.println("Input format: SIGN|KEY|STRING");
            System.out.println("          or: VERIFY|KEY|STRING|<signature>");
        } else if (service.equals("TOPK")) {
            System.out.println("Input format: TOPK|<k>|<text>");
            System.out.println("          or: TFIDF|<k>|<doc1>~~<doc2>~~...");
        } else if (service.equals("CSV")) {
            System.out.println("Input format: <value1>,<value2>,<value3>,...");
        }

        System.out.print("Enter input: ");
        String input = scanner.nextLine().trim();
        if (input.isEmpty()) { System.out.println("Input cannot be empty.\n"); return; }

        byte[] payloadBytes = input.getBytes("UTF-8");
        writeLine(rawOut, "REQUEST|" + service + "|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();

        String responseLine = readLine(rawIn);
        if (!handleResponseHeader(responseLine)) return;

        long   resultLen   = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        rawIn.readFully(resultBytes);
        System.out.println("\n>>> Result: " + new String(resultBytes, "UTF-8"));
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Compression — sends raw bytes with operation byte prefix, no Base64
    // Payload: 'C' byte for compress or 'D' byte for decompress + raw data
    // -------------------------------------------------------------------------
    static void handleCompressionService(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\nCompression Operations: COMPRESS, DECOMPRESS");
        System.out.print("Enter Operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        if (!operation.equals("COMPRESS") && !operation.equals("DECOMPRESS")) {
            System.out.println(">>> Error: Use COMPRESS or DECOMPRESS.\n");
            return;
        }

        System.out.println("\nInput type:");
        System.out.println("1. Text");
        System.out.println("2. File");
        System.out.print("Choice: ");
        String inputType = scanner.nextLine().trim();

        byte[] dataBytes;
        String outputPath  = null;
        boolean isFileInput = inputType.equals("2");

        if (isFileInput) {
            System.out.print("Enter input file path: ");
            String inputPath = scanner.nextLine().trim();
            System.out.print("Enter output file path: ");
            outputPath = scanner.nextLine().trim();

            File f = new File(inputPath);
            if (!f.exists()) { System.out.println(">>> Error: File not found: " + inputPath + "\n"); return; }
            dataBytes = Files.readAllBytes(f.toPath());
            System.out.println("[Client] File read (" + dataBytes.length + " bytes)");
        } else {
            System.out.print("Enter text: ");
            String text = scanner.nextLine();
            if (text.isEmpty()) { System.out.println(">>> Error: Input cannot be empty.\n"); return; }
            dataBytes = text.getBytes("UTF-8");
        }

        // Build payload: operation byte + data
        byte   opByte       = (byte)(operation.equals("COMPRESS") ? 'C' : 'D');
        byte[] payloadBytes = new byte[1 + dataBytes.length];
        payloadBytes[0] = opByte;
        System.arraycopy(dataBytes, 0, payloadBytes, 1, dataBytes.length);

        System.out.println("[Client] Sending " + operation + " (" + payloadBytes.length + " bytes)...");
        writeLine(rawOut, "REQUEST|COMPRESSION|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();
        System.out.println("[Client] Sent. Waiting for result...");

        String responseLine = readLine(rawIn);
        if (!handleResponseHeader(responseLine)) return;

        long   resultLen   = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        rawIn.readFully(resultBytes);

        if (isFileInput && outputPath != null) {
            Files.write(Paths.get(outputPath), resultBytes);
            System.out.println(">>> Success! Saved to: " + outputPath);
            System.out.println(">>> Output size: " + resultBytes.length + " bytes");
        } else {
            if (operation.equals("COMPRESS")) {
                System.out.println(">>> Compressed size: " + resultBytes.length + " bytes");
            } else {
                System.out.println(">>> Decompressed: " + new String(resultBytes, "UTF-8"));
            }
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Image — sends raw bytes with operation line prefix, no Base64
    // Payload: <operation>\n<raw image bytes>
    // -------------------------------------------------------------------------
    static void handleImageService(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\nImage operations: GRAYSCALE, THUMBNAIL, ROTATE:<degrees>, RESIZE:<w>x<h>");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter input image path: ");
        String inputPath = scanner.nextLine().trim();
        System.out.print("Enter output image path: ");
        String outputPath = scanner.nextLine().trim();

        File f = new File(inputPath);
        if (!f.exists()) { System.out.println(">>> Error: File not found: " + inputPath + "\n"); return; }

        byte[] imageBytes    = Files.readAllBytes(f.toPath());
        byte[] operationLine = (operation + "\n").getBytes("UTF-8");
        byte[] payloadBytes  = new byte[operationLine.length + imageBytes.length];
        System.arraycopy(operationLine, 0, payloadBytes, 0,                   operationLine.length);
        System.arraycopy(imageBytes,    0, payloadBytes, operationLine.length, imageBytes.length);

        System.out.println("[Client] Sending image (" + imageBytes.length + " bytes, op=" + operation + ")...");
        writeLine(rawOut, "REQUEST|IMAGE|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();

        System.out.println("[Client] Waiting for result...");

        String responseLine = readLine(rawIn);
        if (!handleResponseHeader(responseLine)) return;

        long   resultLen   = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        rawIn.readFully(resultBytes);
        Files.write(Paths.get(outputPath), resultBytes);

        System.out.println(">>> Success! Saved to: " + outputPath);
        System.out.println(">>> Output size: " + resultBytes.length + " bytes\n");
    }

    // -------------------------------------------------------------------------
    // Shared response header handler
    // Returns true if RESULT|LENGTH, false and prints error otherwise
    // -------------------------------------------------------------------------
    static boolean handleResponseHeader(String responseLine) {
        if (responseLine == null) {
            System.out.println(">>> Error: No response from server.\n");
            return false;
        }
        if (responseLine.startsWith("ERROR|")) {
            System.out.println(">>> Error: " + responseLine.substring(6) + "\n");
            return false;
        }
        if (!responseLine.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + responseLine + "\n");
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Utilities — DataInputStream/DataOutputStream only
    // -------------------------------------------------------------------------
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

    static void writeLine(DataOutputStream out, String s) throws IOException {
        out.write((s + "\n").getBytes("UTF-8"));
        out.flush();
    }
}