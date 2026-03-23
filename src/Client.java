import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CLIENT
 *
 * Run:
 *   javac *.java
 *   java Client <SERVER_IP>
 *
 * Menu option 2 fetches the live service list from the server and shows only
 * currently available services as a numbered menu, preventing invalid requests.
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
                        // Fetch live service list and show only what is currently alive
                        writeLine(rawOut, "LIST");
                        String liveList = readLine(rawIn);
                        if (liveList == null || !liveList.startsWith("SERVICES|")) {
                            System.out.println(">>> Error: Could not retrieve service list.\n");
                            break;
                        }

                        String[] activeServices = liveList.substring(9).split(",");

                        // Descriptions shown next to each service name
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
                            System.out.println(serviceNum + ". " + name
                                + (desc.isEmpty() ? "" : " - " + desc));
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

                        String selected = numberedServices.get(serviceIndex);
                        switch (selected) {
                            case "CSV":         handleCSV(rawOut, rawIn, scanner);         break;
                            case "HMAC":        handleHMAC(rawOut, rawIn, scanner);        break;
                            case "TOPK":        handleTopK(rawOut, rawIn, scanner);        break;
                            case "COMPRESSION": handleCompression(rawOut, rawIn, scanner); break;
                            case "IMAGE":       handleImage(rawOut, rawIn, scanner);       break;
                            default:
                                System.out.println(">>> Error: No handler for: " + selected + "\n");
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
        String inputPath = stripQuotes(scanner.nextLine().trim());

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
    // HMAC — sign or verify using a file as the message
    //
    // SIGN:   key typed. Message read from file using text extraction
    //         (supports .txt, .docx, .pdf). Signature printed and optionally
    //         saved to a file so VERIFY can load it later.
    //
    // VERIFY: key typed. Message read from file (same extraction).
    //         Signature loaded from a file.
    // -------------------------------------------------------------------------
    static void handleHMAC(DataOutputStream rawOut, DataInputStream rawIn, Scanner scanner) throws IOException {
        System.out.println("\n--- HMAC Sign/Verify ---");
        System.out.println("Operations: SIGN, VERIFY");
        System.out.print("Enter operation: ");
        String operation = scanner.nextLine().trim().toUpperCase();

        if (!operation.equals("SIGN") && !operation.equals("VERIFY")) {
            System.out.println(">>> Error: Use SIGN or VERIFY.\n");
            return;
        }

        System.out.print("Enter secret key: ");
        String key = scanner.nextLine().trim();
        if (key.isEmpty()) { System.out.println(">>> Error: Key cannot be empty.\n"); return; }

        System.out.print("Enter input file path (contains the message): ");
        String inputPath = stripQuotes(scanner.nextLine().trim());
        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.out.println(">>> Error: File not found: " + inputPath + "\n");
            return;
        }

        // Use text extraction so .docx/.pdf files work correctly
        String message = readFileAsText(inputFile);
        System.out.println("[Client] Message read from file (" + message.length() + " chars)");

        String payload;
        if (operation.equals("SIGN")) {
            payload = "SIGN|" + key + "|" + message;
        } else {
            // VERIFY — get the signature (paste or load from file)
            String signature;
            
            System.out.print("Enter signature file path: ");
            String sigPath = stripQuotes(scanner.nextLine().trim());
            File sigFile = new File(sigPath);
            if (!sigFile.exists()) {
                System.out.println(">>> Error: File not found: " + sigPath + "\n");
                return;
            }
            // Signature is plain Base64 — read raw bytes, no text extraction
            signature = new String(Files.readAllBytes(sigFile.toPath()), "UTF-8").trim();
            System.out.println("[Client] Signature loaded from: " + sigPath);
            
            payload = "VERIFY|" + key + "|" + message + "|" + signature;
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
        String result = new String(resultBytes, "UTF-8");

        System.out.println("\n>>> Result: " + result);

        // Offer to save the signature to a file after a successful SIGN
        if (operation.equals("SIGN")) {
            System.out.print("Save signature to file? Enter path or press Enter to skip: ");
            String outPath = scanner.nextLine().trim();
            if (!outPath.isEmpty()) {
                Files.write(Paths.get(outPath), result.getBytes("UTF-8"));
                System.out.println(">>> Signature saved to: " + outPath);
            }
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // TOPK — reads text from one or more files
    //
    // Uses readFileAsText() so .docx, .pdf, and .txt all produce clean text.
    // TFIDF asks "how many documents?" upfront — no open-ended prompts.
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
            String inputPath = stripQuotes(scanner.nextLine().trim());
            File f = new File(inputPath);
            if (!f.exists()) { System.out.println(">>> Error: File not found: " + inputPath + "\n"); return; }
            String text = readFileAsText(f);
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
                String path = stripQuotes(scanner.nextLine().trim());
                File f = new File(path);
                if (!f.exists()) { System.out.println(">>> Error: File not found: " + path + "\n"); return; }
                if (docs.length() > 0) docs.append("~~");
                docs.append(readFileAsText(f));
                System.out.println("[Client] Document " + (i + 1) + " read (" + f.length() + " bytes)");
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
    // Payload: 'C' or 'D' byte followed by raw file bytes
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
        String inputPath = stripQuotes(scanner.nextLine().trim());
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
        String inputPath = stripQuotes(scanner.nextLine().trim());
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
    // Check if a service is still alive immediately before sending data.
    // This prevents reading a large file and then discovering the node is down.
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
        System.out.println(">>> Error: Service '" + service + "' is not currently available.\n");
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

    // =========================================================================
    // FILE TEXT EXTRACTION
    //
    // readFileAsText() extracts plain text regardless of file format.
    // Used by TOPK and HMAC so that .docx/.pdf files produce real words
    // instead of ZIP metadata noise (xmlpk, manifest, idat, etc.).
    //
    // .txt .md .csv      — read as UTF-8 directly
    // .docx .xlsx .pptx .odt .epub .zip
    //                    — unzip and strip XML tags
    // .pdf               — extract BT...ET text blocks (heuristic)
    // anything else      — try UTF-8, warn if binary
    // =========================================================================
    static String readFileAsText(File f) throws IOException {
        String name = f.getName().toLowerCase();

        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv")) {
            return new String(Files.readAllBytes(f.toPath()), "UTF-8");
        }
        if (name.endsWith(".docx") || name.endsWith(".xlsx") || name.endsWith(".pptx")
                || name.endsWith(".odt") || name.endsWith(".epub")
                || name.endsWith(".zip") || name.endsWith(".jar")) {
            return extractTextFromZip(f);
        }
        if (name.endsWith(".pdf")) {
            return extractTextFromPdf(f);
        }

        byte[] raw     = Files.readAllBytes(f.toPath());
        String attempt = new String(raw, "UTF-8");
        int    nonPrintable = 0;
        for (int i = 0; i < Math.min(attempt.length(), 1000); i++) {
            char c = attempt.charAt(i);
            if (c < 32 && c != '\n' && c != '\r' && c != '\t') nonPrintable++;
        }
        if (nonPrintable > 20) {
            System.out.println("[Client] WARNING: '" + f.getName()
                + "' looks binary. Text extraction may be poor.");
        }
        return attempt;
    }

    static String extractTextFromZip(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(f))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String en = entry.getName().toLowerCase();
                if (en.endsWith(".png") || en.endsWith(".jpg") || en.endsWith(".gif")
                        || en.endsWith(".emf") || en.endsWith(".wmf") || en.endsWith(".class")) {
                    zip.closeEntry(); continue;
                }
                boolean isMetadata = en.contains("_rels")          || en.contains("[content_types]")
                        || en.contains("app.xml")     || en.contains("core.xml")
                        || en.contains("styles.xml")  || en.contains("settings.xml")
                        || en.contains("theme")       || en.contains("fonttable")
                        || en.contains("websettings") || en.contains("numbering");
                if ((en.endsWith(".xml") || en.endsWith(".txt")) && !isMetadata) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096]; int len;
                    while ((len = zip.read(buf)) != -1) baos.write(buf, 0, len);
                    String text = baos.toString("UTF-8")
                        .replaceAll("<[^>]+>", " ")
                        .replaceAll("&amp;",  "&").replaceAll("&lt;",   "<")
                        .replaceAll("&gt;",   ">").replaceAll("&apos;", "'")
                        .replaceAll("&quot;", "\"").replaceAll("\\s+",  " ").trim();
                    if (!text.isEmpty()) sb.append(text).append(" ");
                }
                zip.closeEntry();
            }
        }
        if (sb.length() == 0) System.out.println("[Client] WARNING: No text found in archive.");
        return sb.toString();
    }

    static String extractTextFromPdf(File f) throws IOException {
        String text = new String(Files.readAllBytes(f.toPath()), "ISO-8859-1");
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (true) {
            int bt = text.indexOf("BT", pos);
            if (bt == -1) break;
            int et = text.indexOf("ET", bt);
            if (et == -1) break;
            String block = text.substring(bt, et);
            int p = 0;
            while (true) {
                int open  = block.indexOf('(', p); if (open  == -1) break;
                int close = block.indexOf(')', open); if (close == -1) break;
                String word = block.substring(open + 1, close).trim();
                if (!word.isEmpty()) sb.append(word).append(" ");
                p = close + 1;
            }
            pos = et + 2;
        }
        String result = sb.toString().trim();
        if (result.isEmpty()) {
            System.out.println("[Client] WARNING: Could not extract text from PDF "
                + "(may use compressed streams). Results may be limited.");
            result = new String(Files.readAllBytes(f.toPath()), "UTF-8");
        }
        return result;
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Strip surrounding quotes from a path (handles copy-paste from Windows Explorer) */
    static String stripQuotes(String s) {
        return s.replaceAll("^\"|\"$", "");
    }

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