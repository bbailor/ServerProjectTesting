import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Base64;
import java.util.Scanner;

/**
 * DEMO CLIENT
 *
 * Runs on: EC2 Instance 2, or any machine (even your own laptop for testing)
 *
 * What this does:
 *   - Connects to the server over TCP
 *   - Asks the server for a list of available services
 *   - Lets the user pick a service and enter input
 *   - Sends the request to the server, which routes it to the right Service Node
 *   - Displays the result
 *
 * Run:
 *   javac Client.java
 *   java Client <SERVER_IP>
 *
 * Example:
 *   java Client 54.123.45.67
 */
public class Client {

    static final int SERVER_TCP_PORT = 9000; // must match Server.TCP_PORT

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Client <serverIp>");
            System.out.println("Example: java Client 54.123.45.67");
            System.exit(1);
        }

        String serverIp = args[0];
        Scanner scanner = new Scanner(System.in);

        System.out.println("[Client] Connecting to server at " + serverIp + ":" + SERVER_TCP_PORT);

        // Connect to the server — this TCP connection stays open for the session
        try (
            Socket socket = new Socket(serverIp, SERVER_TCP_PORT);
            PrintWriter    out = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"), 1024 * 1024)
        ) {
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
                        listServices(out, in);
                        break;
                    case "2":
                        requestService(out, in, scanner);
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
    static void listServices(PrintWriter out, BufferedReader in) throws IOException {
        System.out.println("\n[Client] Requesting service list...");
        out.println("LIST");

        String response = in.readLine();
        System.out.println("[Client] Server responded: " + response);

        if (response.startsWith("SERVICES|")) {
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
    static void requestService(PrintWriter out, BufferedReader in, Scanner scanner) throws IOException {
        System.out.print("\nEnter service name: ");
        String service = scanner.nextLine().trim().toUpperCase();

        if (service.isEmpty()) {
            System.out.println("Service name cannot be empty.\n");
            return;
        }

        // Check if service is available before asking for input
        out.println("LIST");
        String listResponse = in.readLine();
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

        // IMAGE service needs file I/O instead of text input
        if (service.equals("IMAGE")) {
            handleImageService(out, in, scanner);
            return;
        }

        // COMPRESSION service supporting text and file input
        if (service.equals("COMPRESSION")) {
            handleCompressionService(out, in, scanner);
            return;
        }

        // Gives example input for user friendliness
        if (service.equals("HMAC")) {
            System.out.println("Input format: SIGN|KEY|STRING");
            System.out.println("-or\t\tVERIFY|KEY|STRING|<paste signature here>");
        }
        if (service.equals("TOPK")) {
            System.out.println("Input format: TOPK|<k>|<text> or TFIDF|<k>|<docs>\nSee READ.ME for more information.");
        }

        if (service.equals("CSV")) {
            System.out.println("Input format: <value1>,<value2>,...");

        }


        System.out.print("Enter input: ");
        String input = scanner.nextLine().trim();

        if (input.isEmpty()) {
            System.out.println("Input cannot be empty.\n");
            return;
        }

        // Send:  REQUEST|<serviceName>|<input>
        String request = "REQUEST|" + service + "|" + input;
        System.out.println("[Client] Sending: " + request);
        out.println(request);

        // Wait for response
        String response = in.readLine();
        System.out.println("[Client] Response: " + response);

        if (response != null && response.startsWith("RESULT|")) {
            System.out.println("\n>>> Result: " + response.substring(7));
        } else if (response != null && response.startsWith("ERROR|")) {
            System.out.println("\n>>> Error: " + response.substring(6));
        }
        System.out.println();
    }

    // ------------------------------------------------
    // Compression Service Handler
    // Support for File and String Text Input
    // Operations: COMPRESS, DECOMPRESS
    // ------------------------------------------------
    static void handleCompressionService(PrintWriter out, BufferedReader in, Scanner scanner) throws IOException {
        System.out.println("\nCompression Opertaions: COMPRESS, DECOPRESS");
        System.out.print("Enter Operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        if(!operation.equals("COMPRESS") && !operation.equals("DECOMPRESS")) {
            System.out.println(">>> Error: Invalid operation. Use COMPRESS or DECOMPRESS.\n");
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
            // File input
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

            // Read file contents
            byte[] fileBytes = Files.readAllBytes(inputFile.toPath());
            
            if (operation.equals("COMPRESS")) {
                // For compression: encode file bytes as Base64 so they can travel over text protocol
                // The service will receive this, decode it, compress, and return Base64-encoded compressed data
                inputData = "FILE:" + Base64.getEncoder().encodeToString(fileBytes);
            } else {
                // For decompression: the file should contain Base64-encoded compressed data
                // Read it as text
                inputData = new String(fileBytes, "UTF-8").trim();
            }
            
            System.out.println("[Client] File read (" + fileBytes.length + " bytes)");
        } else {
            // Text input
            System.out.print("Enter text: ");
            inputData = scanner.nextLine();

            if (inputData.isEmpty()) {
                System.out.println(">>> Error: Input cannot be empty.\n");
                return;
            }
        }

        // Build Request
        // For DECOMPRESS, we prefix with DECOMPRESS| so the service node knows
        String payload;
        if (operation.equals("DECOMPRESS")) {
            payload = "DECOMPRESS|" + inputData;
        } else {
            payload = inputData;
        }

        String request = "REQUEST|COMPRESSION|" + payload;
        System.out.println("[Client] Sending compression request (operation=" + operation + ")...");
        out.println(request);

        // Wait for response
        System.out.println("[Client] Waiting for result...");
        String response = in.readLine();

        if (response == null) {
            System.out.println(">>> Error: No response from server.\n");
            return;
        }

        if (response.startsWith("ERROR|") || response.contains("ERROR|")) {
            String msg = response.startsWith("RESULT|ERROR|")
                ? response.substring(13)
                : response.substring(response.indexOf("ERROR|") + 6);
            System.out.println(">>> Error: " + msg + "\n");
            return;
        }

        if (!response.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + response + "\n");
            return;
        }

        String result = response.substring(7);

        if (isFileInput && outputPath != null) {
            // Save result to file
            if (operation.equals("COMPRESS")) {
                // Result is Base64-encoded compressed data - save as text
                Files.write(Paths.get(outputPath), result.getBytes("UTF-8"));
                System.out.println(">>> Success! Compressed data saved to: " + outputPath);
            } else {
                // Result is decompressed text - save directly
                // Check if it was originally a file (Base64 encoded)
                if (result.startsWith("FILE:")) {
                    // Decode the Base64 back to original file bytes
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
            // Display result directly
            if (operation.equals("COMPRESS")) {
                System.out.println("\n>>> Compressed (Base64): " + result);
                System.out.println(">>> (You can use this string to decompress later)");
            } else {
                System.out.println("\n>>> Decompressed: " + result);
            }
        }
        System.out.println();
    }


    // -------------------------------------------------------------------------
    // Image service handler — added to support ImageServiceNode
    // Prompts for operation, input file path, and output file path
    // Encodes image to Base64 before sending, decodes result back to a file
    // -------------------------------------------------------------------------
    static void handleImageService(PrintWriter out, BufferedReader in, Scanner scanner) throws IOException {
        System.out.println("\nImage operations: grayscale, thumbnail, rotate:<degrees>, resize:<w>x<h>");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        System.out.print("Enter input image path (e.g. photo.png): ");
        String inputPath = scanner.nextLine().trim();

        System.out.print("Enter output image path (e.g. result.png): ");
        String outputPath = scanner.nextLine().trim();

        // Check the file exists before doing anything
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        // Read the image and encode it to Base64 so it can travel over the text protocol
        System.out.println("[Client] Reading image: " + inputPath);
        byte[] imageBytes = Files.readAllBytes(inputFile.toPath());
        String b64Image   = Base64.getEncoder().encodeToString(imageBytes);
        System.out.println("[Client] Image encoded (" + imageBytes.length + " bytes)");

        // Send: REQUEST|IMAGE|OPERATION:<base64>
        String request = "REQUEST|IMAGE|" + operation + ":" + b64Image;
        System.out.println("[Client] Sending image request (operation=" + operation + ")...");
        out.println(request);

        // Wait for result
        System.out.println("[Client] Waiting for result...");
        String response = in.readLine();

        if (response == null) {
            System.out.println(">>> Error: No response from server.\n");
            return;
        }

        if (response.startsWith("RESULT|ERROR|") || response.startsWith("ERROR|")) {
            String msg = response.startsWith("RESULT|ERROR|")
                ? response.substring(13)
                : response.substring(6);
            System.out.println(">>> Error: " + msg + "\n");
            return;
        }

        if (!response.startsWith("RESULT|")) {
            System.out.println(">>> Unexpected response: " + response + "\n");
            return;
        }

        // Decode the result image and save it to the output file
        String resultB64   = response.substring(7);
        byte[] resultBytes = Base64.getDecoder().decode(resultB64);
        Files.write(Paths.get(outputPath), resultBytes);

        System.out.println(">>> Success! Result saved to: " + outputPath);
        System.out.println(">>> Output size: " + resultBytes.length + " bytes\n");
    }
}