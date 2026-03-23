import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;

/**
 * CLIENT
 *
 * All service inputs are read from files.
 * Results are saved to output files.
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
    // Fetch live service list from server
    writeLine(rawOut, "LIST");
    String liveList = readLine(rawIn);
    if (liveList == null || !liveList.startsWith("SERVICES|")) {
        System.out.println(">>> Error: Could not retrieve service list.\n");
        break;
    }

    // Build a list of only the services that are currently alive
    String[] activeServices = liveList.substring(9).split(",");

    // Map each active service to its handler number and description
    java.util.LinkedHashMap<String, String> serviceMap = new java.util.LinkedHashMap<>();
    serviceMap.put("CSV",         "Analyze numbers from a file");
    serviceMap.put("HMAC",        "Sign or verify a file");
    serviceMap.put("TOPK",        "Find top words in a text file");
    serviceMap.put("COMPRESSION", "Compress or decompress a file");
    serviceMap.put("IMAGE",       "Transform an image file");

    System.out.println("\n--- Currently Available Services ---");
    java.util.List<String> numberedServices = new java.util.ArrayList<>();
    int serviceNum = 1;
    for (String active : activeServices) {
        String name = active.trim().toUpperCase();
        String desc = serviceMap.getOrDefault(name, "");
        System.out.println(serviceNum + ". " + name + (desc.isEmpty() ? "" : " - " + desc));
        numberedServices.add(name);
        serviceNum++;
    }

    System.out.print("Choose service (number): ");
    String serviceChoice = scanner.nextLine().trim();

    int serviceIndex;
    try {
        serviceIndex = Integer.parseInt(serviceChoice) - 1;
    } catch (NumberFormatException e) {
        System.out.println("Invalid choice.\n");
        break;
    }

    if (serviceIndex < 0 || serviceIndex >= numberedServices.size()) {
        System.out.println("Invalid choice.\n");
        break;
    }

    String selectedService = numberedServices.get(serviceIndex);
    switch (selectedService) {
        case "CSV":         handleCSV(rawOut, rawIn, scanner);         break;
        case "HMAC":        handleHMAC(rawOut, rawIn, scanner);        break;
        case "TOPK":        handleTopK(rawOut, rawIn, scanner);        break;
        case "COMPRESSION": handleCompression(rawOut, rawIn, scanner); break;
        case "IMAGE":       handleImage(rawOut, rawIn, scanner);       break;
        default: System.out.println(">>> Error: No handler for service: " + selectedService + "\n");
    }
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
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // CSV — reads comma-separated numbers from a file
    // -------------------------------------------------------------------------
    static void handleCSV(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- CSV Statistics ---");
        System.out.println("Input file should contain comma-separated or newline-separated numbers.");
        System.out.print("Enter input file path: ");
        String inputPath = scanner.nextLine().trim();

        inputPath = inputPath.replaceAll("^\"|\"$", "");      

        File inputFile = new File(inputPath);

        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        byte[] fileBytes = Files.readAllBytes(inputFile.toPath());
        System.out.println("[Client] File read (" + fileBytes.length + " bytes)");

        if (!checkServiceAvailable("CSV", rawOut, rawIn)) return;

        writeLine(rawOut, "REQUEST|CSV|" + fileBytes.length);
        rawOut.write(fileBytes);
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
    // HMAC — reads message from a file, key from terminal
    // -------------------------------------------------------------------------
    static void handleHMAC(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- HMAC Sign/Verify ---");
        System.out.println("Operations: SIGN, VERIFY");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter secret key: ");
        String key = scanner.nextLine().trim();

        System.out.print("Enter input file path (contains the message): ");
        String inputPath = scanner.nextLine().trim();

        inputPath = inputPath.replaceAll("^\"|\"$", "");      

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        String message = new String(Files.readAllBytes(inputFile.toPath()), "UTF-8").trim();
        System.out.println("[Client] Message read from file (" + message.length() + " chars)");

        String payload;
        if (operation.equals("SIGN")) {
            payload = "SIGN|" + key + "|" + message;
        } else if (operation.equals("VERIFY")) {
            System.out.print("Enter signature to verify: ");
            String signature = scanner.nextLine().trim();
            payload = "VERIFY|" + key + "|" + message + "|" + signature;
        } else {
            System.out.println(">>> Error: Use SIGN or VERIFY.\n");
            return;
        }

        if (!checkServiceAvailable("HMAC", rawOut, rawIn)) return;

        byte[] payloadBytes = payload.getBytes("UTF-8");
        writeLine(rawOut, "REQUEST|HMAC|" + payloadBytes.length);
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
    // TOPK — reads text from a file
    // -------------------------------------------------------------------------
    static void handleTopK(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- Top-K Terms / TF-IDF ---");
        System.out.println("Operations:");
        System.out.println("  TOPK  - Find top K words in a single file");
        System.out.println("  TFIDF - Rank words by importance across multiple files");
        System.out.print("Enter operation (TOPK or TFIDF): ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter K (how many top words to return): ");
        String k = scanner.nextLine().trim();

        String payload;

        if (operation.equals("TOPK")) {
            System.out.print("Enter input file path: ");
            String inputPath = scanner.nextLine().trim();
            inputPath = inputPath.replaceAll("^\"|\"$", "");      
            File f = new File(inputPath);
            if (!f.exists()) { System.out.println(">>> Error: File not found: " + inputPath + "\n"); return; }
            String text = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            payload = "TOPK|" + k + "|" + text;
            System.out.println("[Client] File read (" + text.length() + " chars)");

        } else if (operation.equals("TFIDF")) {
            System.out.print("How many documents to compare? ");
            int numDocs;
            try { numDocs = Integer.parseInt(scanner.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.println(">>> Error: Enter a number.\n"); return; }

            StringBuilder docs = new StringBuilder();
            for (int i = 0; i < numDocs; i++) {
                System.out.print("Enter file path for document " + (i + 1) + ": ");
                String path = scanner.nextLine().trim();
                File f = new File(path);
                if (!f.exists()) { System.out.println(">>> Error: File not found: " + path + "\n"); return; }
                if (docs.length() > 0) docs.append("~~");
                docs.append(new String(Files.readAllBytes(f.toPath()), "UTF-8"));
            }
            payload = "TFIDF|" + k + "|" + docs.toString();

        } else {
            System.out.println(">>> Error: Use TOPK or TFIDF.\n");
            return;
        }

        if (!checkServiceAvailable("TOPK", rawOut, rawIn)) return;

        byte[] payloadBytes = payload.getBytes("UTF-8");
        writeLine(rawOut, "REQUEST|TOPK|" + payloadBytes.length);
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
    // COMPRESSION — compresses or decompresses a file
    // Payload: 'C' byte for compress or 'D' byte + raw file bytes
    // -------------------------------------------------------------------------
    static void handleCompression(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- Compression ---");
        System.out.println("Operations: COMPRESS, DECOMPRESS");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        if (!operation.equals("COMPRESS") && !operation.equals("DECOMPRESS")) {
            System.out.println(">>> Error: Use COMPRESS or DECOMPRESS.\n");
            return;
        }

        System.out.print("Enter input file path: ");
        String inputPath = scanner.nextLine().trim();
        inputPath = inputPath.replaceAll("^\"|\"$", "");      
        System.out.print("Enter output file path: ");
        String outputPath = scanner.nextLine().trim();

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        byte[] dataBytes    = Files.readAllBytes(inputFile.toPath());
        byte   opByte       = (byte)(operation.equals("COMPRESS") ? 'C' : 'D');
        byte[] payloadBytes = new byte[1 + dataBytes.length];
        payloadBytes[0] = opByte;
        System.arraycopy(dataBytes, 0, payloadBytes, 1, dataBytes.length);

        System.out.println("[Client] File read (" + dataBytes.length + " bytes)");
        System.out.println("[Client] Sending " + operation + " request...");

        if (!checkServiceAvailable("COMPRESSION", rawOut, rawIn)) return;

        writeLine(rawOut, "REQUEST|COMPRESSION|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();
        System.out.println("[Client] Sent. Waiting for result...");

        String responseLine = readLine(rawIn);
        if (!handleResponseHeader(responseLine)) return;

        long   resultLen   = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        rawIn.readFully(resultBytes);

        Files.write(Paths.get(outputPath), resultBytes);
        System.out.println(">>> Success! Saved to: " + outputPath);
        System.out.println(">>> Output size: " + resultBytes.length + " bytes");
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // IMAGE — transforms an image file
    // Payload: <operation>\n<raw image bytes>
    // -------------------------------------------------------------------------
    static void handleImage(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- Image Transform ---");
        System.out.println("Operations: GRAYSCALE, THUMBNAIL, ROTATE:<degrees>, RESIZE:<w>x<h>");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter input image file path: ");
        String inputPath = scanner.nextLine().trim();
        inputPath = inputPath.replaceAll("^\"|\"$", "");      
        System.out.print("Enter output image file path: ");
        String outputPath = scanner.nextLine().trim();

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        byte[] imageBytes    = Files.readAllBytes(inputFile.toPath());
        byte[] operationLine = (operation + "\n").getBytes("UTF-8");
        byte[] payloadBytes  = new byte[operationLine.length + imageBytes.length];
        System.arraycopy(operationLine, 0, payloadBytes, 0,                   operationLine.length);
        System.arraycopy(imageBytes,    0, payloadBytes, operationLine.length, imageBytes.length);

        System.out.println("[Client] Image read (" + imageBytes.length + " bytes)");

        if (!checkServiceAvailable("IMAGE", rawOut, rawIn)) return;

        writeLine(rawOut, "REQUEST|IMAGE|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();
        System.out.println("[Client] Sent. Waiting for result...");

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
    // Check if a service is available before sending data
    // -------------------------------------------------------------------------
    static boolean checkServiceAvailable(String service, DataOutputStream rawOut, DataInputStream rawIn) throws IOException {
        writeLine(rawOut, "LIST");
        String listResponse = readLine(rawIn);
        if (listResponse == null || !listResponse.startsWith("SERVICES|")) {
            System.out.println(">>> Error: Could not retrieve service list.\n");
            return false;
        }
        for (String s : listResponse.substring(9).split(",")) {
            if (s.trim().equalsIgnoreCase(service)) return true;
        }
        System.out.println(">>> Error: Service '" + service + "' is not currently available.");
        System.out.println(">>> Type 1 from the menu to see available services.\n");
        return false;
    }

    // -------------------------------------------------------------------------
    // Shared response header handler
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
    // Utilities
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