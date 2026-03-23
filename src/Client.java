import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Scanner;

/**
 * CLIENT
 *
 * Runs on: EC2 Instance 2, or any machine
 *
 * Run:
 *   javac Client.java
 *   java Client <SERVER_IP>
 *
 * Example:
 *   java Client 54.123.45.67
 */
public class Client {

    static final int SERVER_TCP_PORT = 9000;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Client <serverIp>");
            System.out.println("Example: java Client 54.123.45.67");
            System.exit(1);
        }

        String serverIp = args[0];
        Scanner scanner = new Scanner(System.in);

        System.out.println("[Client] Connecting to server at " + serverIp + ":" + SERVER_TCP_PORT);

        try (Socket socket = new Socket(serverIp, SERVER_TCP_PORT)) {
            socket.setSoTimeout(1_800_000); // 30 minutes for large file operations
            socket.setTcpNoDelay(true);

            OutputStream rawOut = socket.getOutputStream();
            InputStream  rawIn  = socket.getInputStream();

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
                        requestService(socket, rawOut, rawIn, scanner);
                        break;
                    case "3":
                        System.out.println("[Client] Disconnecting.");
                        return;
                    default:
                        System.out.println("Invalid choice.\n");
                }
            }
        } catch (ConnectException e) {
            System.err.println("[Client] Could not connect to server at " + serverIp + ":" + SERVER_TCP_PORT);
            System.err.println("         Is the server running? Is the IP correct?");
        }
    }

    // -------------------------------------------------------------------------
    // Ask the server for the current list of alive services
    // -------------------------------------------------------------------------
    static void listServices(OutputStream rawOut, InputStream rawIn) throws IOException {
        System.out.println("\n[Client] Requesting service list...");
        writeLine(rawOut, "LIST");

        String response = readLine(rawIn);
        System.out.println("[Client] Server responded: " + response);

        if (response != null && response.startsWith("SERVICES|")) {
            String[] services = response.substring(9).split(",");
            System.out.println("\nAvailable services:");
            for (String s : services) {
                System.out.println("  - " + s);
            }
        } else {
            System.out.println("  " + response);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Request a specific service with user-provided input
    // -------------------------------------------------------------------------
    static void requestService(Socket socket, OutputStream rawOut, InputStream rawIn, Scanner scanner) throws IOException {
        System.out.print("\nEnter service name: ");
        String service = scanner.nextLine().trim().toUpperCase();

        if (service.isEmpty()) {
            System.out.println("Service name cannot be empty.\n");
            return;
        }

        // Check if service is available before asking for input
        writeLine(rawOut, "LIST");
        String listResponse = readLine(rawIn);
        if (listResponse == null || !listResponse.startsWith("SERVICES|")) {
            System.out.println(">>> Error: Could not retrieve service list.\n");
            return;
        }
        String[] availableServices = listResponse.substring(9).split(",");
        boolean found = false;
        for (String s : availableServices) {
            if (s.trim().equalsIgnoreCase(service)) {
                found = true;
                break;
            }
        }
        if (!found) {
            System.out.println(">>> Error: Service '" + service + "' is not currently available.");
            System.out.println(">>> Type 1 from the menu to see available services.\n");
            return;
        }

        // Route to specific handlers for services that need special input handling
        if (service.equals("IMAGE")) {
            handleImageService(rawOut, rawIn, scanner);
            return;
        }

        if (service.equals("COMPRESSION")) {
            handleCompressionService(rawOut, rawIn, scanner);
            return;
        }

        // Show input format hints for each service
        if (service.equals("HMAC")) {
            System.out.println("Input format: SIGN|KEY|STRING");
            System.out.println("          or: VERIFY|KEY|STRING|<paste signature here>");
        }
        if (service.equals("TOPK")) {
            System.out.println("Input format: TOPK|<k>|<text>");
            System.out.println("          or: TFIDF|<k>|<doc1>~~<doc2>~~...");
        }
        if (service.equals("CSV")) {
            System.out.println("Input format: <value1>,<value2>,<value3>,...");
        }

        System.out.print("Enter input: ");
        String input = scanner.nextLine().trim();

        if (input.isEmpty()) {
            System.out.println("Input cannot be empty.\n");
            return;
        }

        // Send: REQUEST|SERVICE|LENGTH\n<raw bytes>
        byte[] payloadBytes = input.getBytes("UTF-8");
        writeLine(rawOut, "REQUEST|" + service + "|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();

        // Read response: RESULT|LENGTH\n<raw bytes>
        String responseLine = readLine(rawIn);

        if (responseLine == null) {
            System.out.println(">>> Error: No response from server.\n");
            return;
        }
        if (responseLine.startsWith("ERROR|")) {
            System.out.println(">>> Error: " + responseLine.substring(6) + "\n");
            return;
        }
        if (!responseLine.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + responseLine + "\n");
            return;
        }

        long resultLen = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        new DataInputStream(rawIn).readFully(resultBytes);
        System.out.println("\n>>> Result: " + new String(resultBytes, "UTF-8"));
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Compression Service Handler
    // Supports COMPRESS and DECOMPRESS on both text and files
    // -------------------------------------------------------------------------
    static void handleCompressionService(OutputStream rawOut, InputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\nCompression Operations: COMPRESS, DECOMPRESS");
        System.out.print("Enter Operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        if (!operation.equals("COMPRESS") && !operation.equals("DECOMPRESS")) {
            System.out.println(">>> Error: Invalid operation. Use COMPRESS or DECOMPRESS.\n");
            return;
        }

        System.out.println("\nInput type:");
        System.out.println("1. Text (enter directly)");
        System.out.println("2. File (provide file path)");
        System.out.print("Choice: ");
        String inputType = scanner.nextLine().trim();

        String inputData;
        String outputPath = null;
        boolean isFileInput = false;

        if (inputType.equals("2")) {
            isFileInput = true;
            System.out.print("Enter input file path: ");
            String inputPath = scanner.nextLine().trim();

            System.out.print("Enter output file path: ");
            outputPath = scanner.nextLine().trim();

            File inputFile = new File(inputPath);
            if (!inputFile.exists()) {
                System.out.println(">>> Error: File not found: " + inputPath + "\n");
                return;
            }

            byte[] fileBytes = Files.readAllBytes(inputFile.toPath());

            if (operation.equals("COMPRESS")) {
                inputData = "FILE:" + Base64.getEncoder().encodeToString(fileBytes);
            } else {
                inputData = new String(fileBytes, "UTF-8").trim();
            }

            System.out.println("[Client] File read (" + fileBytes.length + " bytes)");
        } else {
            System.out.print("Enter text: ");
            inputData = scanner.nextLine();

            if (inputData.isEmpty()) {
                System.out.println(">>> Error: Input cannot be empty.\n");
                return;
            }
        }

        // Build payload
        String payload = operation.equals("DECOMPRESS") ? "DECOMPRESS|" + inputData : inputData;

        System.out.println("[Client] Sending compression request (operation=" + operation + ")...");
        byte[] payloadBytes = payload.getBytes("UTF-8");
        System.out.println("[Client] Payload length: " + payloadBytes.length + " bytes");

        // Send: REQUEST|COMPRESSION|LENGTH\n<raw bytes>
        writeLine(rawOut, "REQUEST|COMPRESSION|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();
        System.out.println("[Client] Sent " + payloadBytes.length + " bytes");

        System.out.println("[Client] Waiting for result...");

        String responseLine = readLine(rawIn);

        if (responseLine == null) {
            System.out.println(">>> Error: No response from server.\n");
            return;
        }
        if (responseLine.startsWith("ERROR|")) {
            System.out.println(">>> Error: " + responseLine.substring(6) + "\n");
            return;
        }
        if (!responseLine.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + responseLine + "\n");
            return;
        }

        long resultLen = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        new DataInputStream(rawIn).readFully(resultBytes);
        String result = new String(resultBytes, "UTF-8");

        if (isFileInput && outputPath != null) {
            if (operation.equals("COMPRESS")) {
                Files.write(Paths.get(outputPath), result.getBytes("UTF-8"));
                System.out.println(">>> Success! Compressed data saved to: " + outputPath);
            } else {
                if (result.startsWith("FILE:")) {
                    byte[] originalBytes = Base64.getDecoder().decode(result.substring(5));
                    Files.write(Paths.get(outputPath), originalBytes);
                    System.out.println(">>> Success! Decompressed file saved to: " + outputPath);
                    System.out.println(">>> Output size: " + originalBytes.length + " bytes");
                } else {
                    Files.write(Paths.get(outputPath), result.getBytes("UTF-8"));
                    System.out.println(">>> Success! Decompressed text saved to: " + outputPath);
                }
            }
        } else {
            if (operation.equals("COMPRESS")) {
                System.out.println("\n>>> Compressed (Base64): " + result);
                System.out.println(">>> (Use this string with DECOMPRESS to restore the original)");
            } else {
                System.out.println("\n>>> Decompressed: " + result);
            }
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Image Service Handler
    // Prompts for operation, input file, and output file
    // Encodes image to Base64 before sending, decodes result back to a file
    // -------------------------------------------------------------------------
    static void handleImageService(OutputStream rawOut, InputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\nImage operations: GRAYSCALE, THUMBNAIL, ROTATE:<degrees>, RESIZE:<w>x<h>");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter input image path (e.g. photo.png): ");
        String inputPath = scanner.nextLine().trim();

        System.out.print("Enter output image path (e.g. result.png): ");
        String outputPath = scanner.nextLine().trim();

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        System.out.println("[Client] Reading image: " + inputPath);
        byte[] imageBytes = Files.readAllBytes(inputFile.toPath());
        String b64Image   = Base64.getEncoder().encodeToString(imageBytes);
        System.out.println("[Client] Image encoded (" + imageBytes.length + " bytes)");

        String payload      = operation + ":" + b64Image;
        byte[] payloadBytes = payload.getBytes("UTF-8");

        System.out.println("[Client] Sending image request (operation=" + operation + ")...");

        // Send: REQUEST|IMAGE|LENGTH\n<raw bytes>
        writeLine(rawOut, "REQUEST|IMAGE|" + payloadBytes.length);
        rawOut.write(payloadBytes);
        rawOut.flush();

        System.out.println("[Client] Waiting for result...");

        String responseLine = readLine(rawIn);

        if (responseLine == null) {
            System.out.println(">>> Error: No response from server.\n");
            return;
        }
        if (responseLine.startsWith("ERROR|")) {
            System.out.println(">>> Error: " + responseLine.substring(6) + "\n");
            return;
        }
        if (!responseLine.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + responseLine + "\n");
            return;
        }

        long resultLen = Long.parseLong(responseLine.split("\\|")[1]);
        byte[] resultBytes = new byte[(int) resultLen];
        new DataInputStream(rawIn).readFully(resultBytes);
        String resultB64        = new String(resultBytes, "UTF-8");
        byte[] resultImageBytes = Base64.getDecoder().decode(resultB64);
        Files.write(Paths.get(outputPath), resultImageBytes);

        System.out.println(">>> Success! Result saved to: " + outputPath);
        System.out.println(">>> Output size: " + resultImageBytes.length + " bytes\n");
    }

    // -------------------------------------------------------------------------
    // Utilities — same pattern as Server and ServiceNode
    // -------------------------------------------------------------------------
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
        out.flush();
    }
}