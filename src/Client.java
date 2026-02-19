import java.io.*;
import java.net.*;
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
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()))
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
        System.out.print("\nEnter service name (e.g. BASE64, UPPERCASE, REVERSE, WORDCOUNT): ");
        String service = scanner.nextLine().trim().toUpperCase();

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
}