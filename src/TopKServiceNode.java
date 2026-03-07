import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * TOP-K TERMS / TF-IDF SERVICE NODE
 *
 * Registration name: TOPK
 *
 * Supported operations (sent as TASK|<operation>|...):
 *
 *   TOPK|<k>|<text>
 *       → Returns the top-K most frequent terms in <text>, sorted by frequency
 *         (descending). Common English stop-words are filtered out.
 *         Returns: term1:count1, term2:count2, ...
 *
 *   TFIDF|<k>|<doc1>~~<doc2>~~...
 *       → Computes TF-IDF scores for terms across multiple documents
 *         separated by the ~~ character. Returns top-K terms by TF-IDF
 *         score from the first document.
 *         Returns: term1:score1, term2:score2, ...
 *
 * The node returns: RESULT|<o>
 * On error:         RESULT|ERROR|<reason>
 *
 * Run:
 *   javac TopKServiceNode.java
 *   java TopKServiceNode <serverIp> <myTcpPort> [filter]
 *
 * The optional third argument controls stop-word filtering:
 *   filter=on  (default) — common English words like "the", "and" are removed
 *   filter=off           — all words are counted, including stop-words
 *
 * Example:
 *   java TopKServiceNode 127.0.0.1 9104          (filtering ON by default)
 *   java TopKServiceNode 127.0.0.1 9104 filter=off
 *   java TopKServiceNode 127.0.0.1 9104 filter=on
 *
 * Client usage examples:
 *   Service: TOPK
 *   Input:   TOPK|5|The quick brown fox jumps over the lazy dog
 *   Input:   TFIDF|5|Machine learning is great~~Deep learning is also great~~Learning never stops
 */
public class TopKServiceNode {

    static final String SERVICE_NAME = "TOPK";
    static final int    SERVER_UDP   = 9001;  // must match Server.UDP_HB_PORT

    static String serverIp;
    static int    myTcpPort;
    static String nodeId;

    static final Random random = new Random();

    // Whether to filter common English stop-words (default: true)
    // Controlled by the optional "filter=on|off" launch argument
    static boolean filterStopWords = true;

    // Common English stop-words to filter from term counts
    static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "from", "is", "was", "are", "were", "be", "been",
        "being", "have", "has", "had", "do", "does", "did", "will", "would",
        "could", "should", "may", "might", "shall", "can", "need", "dare",
        "it", "its", "this", "that", "these", "those", "i", "you", "he",
        "she", "we", "they", "me", "him", "her", "us", "them", "my", "your",
        "his", "our", "their", "what", "which", "who", "whom", "when",
        "where", "why", "how", "all", "each", "every", "both", "few", "more",
        "most", "other", "some", "such", "no", "not", "only", "same", "so",
        "than", "too", "very", "just", "also", "as", "up", "if", "about"
    ));

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java TopKServiceNode <serverIp> <myTcpPort> [filter=on|off]");
            System.out.println("Example: java TopKServiceNode 127.0.0.1 9104");
            System.out.println("         java TopKServiceNode 127.0.0.1 9104 filter=off");
            System.exit(1);
        }

        serverIp  = args[0];
        myTcpPort = Integer.parseInt(args[1]);
        nodeId    = "SN-" + SERVICE_NAME + "-" + myTcpPort;

        // Optional third arg: filter=on (default) or filter=off
        if (args.length >= 3) {
            String filterArg = args[2].trim().toLowerCase();
            if (filterArg.equals("filter=off")) {
                filterStopWords = false;
            } else if (filterArg.equals("filter=on")) {
                filterStopWords = true;
            } else {
                System.out.println("[" + nodeId + "] WARNING: Unrecognised argument '" + args[2] + "'. Expected filter=on or filter=off. Defaulting to filter=on.");
            }
        }

        System.out.println("[" + nodeId + "] Starting Top-K Terms / TF-IDF Service Node...");
        System.out.println("[" + nodeId + "] Server: " + serverIp + ":" + SERVER_UDP);
        System.out.println("[" + nodeId + "] TCP task port: " + myTcpPort);
        System.out.println("[" + nodeId + "] Stop-word filtering: " + (filterStopWords ? "ON" : "OFF"));

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
        scheduler.execute(TopKServiceNode::sendHeartbeat);
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
            String msg  = "HEARTBEAT|" + nodeId + "|" + SERVICE_NAME + "|" + myTcpPort;
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
    //   Node replies:   RESULT|<o>   or   RESULT|ERROR|<reason>
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
            BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream(),  "UTF-8"));
            PrintWriter    out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), "UTF-8"), true)
        ) {
            String line = in.readLine();
            if (line == null) return;

            System.out.println("[" + nodeId + "] Task received (length=" + line.length() + ")");

            if (!line.startsWith("TASK|")) {
                out.println("RESULT|ERROR|Expected format: TASK|TOPK|<k>|<text> or TASK|TFIDF|<k>|<docs>");
                return;
            }

            String input  = line.substring(5).trim(); // everything after "TASK|"
            String result = processTask(input);

            out.println("RESULT|" + result);
            System.out.println("[" + nodeId + "] Result sent: " + result);

        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Task handling error: " + e.getMessage());
        } finally {
            try { conn.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Top-K / TF-IDF Service Logic
    //
    // TOPK|<k>|<text>
    //   - Tokenises <text> into lower-case alphabetic terms
    //   - Filters stop-words
    //   - Counts term frequencies and returns top-K
    //
    // TFIDF|<k>|<doc1>~~<doc2>~~...
    //   - Splits input on ~~ to get individual documents
    //   - Computes TF for each term in doc[0], IDF across all docs
    //   - TF-IDF = TF * IDF  (log-smoothed IDF: log((N+1)/(df+1)) + 1)
    //   - Returns top-K terms from doc[0] by TF-IDF score
    // -------------------------------------------------------------------------
    static String processTask(String input) {
        try {
            String upper = input.toUpperCase();

            if (upper.startsWith("TOPK|")) {
                return handleTopK(input.substring(5)); // strip "TOPK|"

            } else if (upper.startsWith("TFIDF|")) {
                return handleTfIdf(input.substring(6)); // strip "TFIDF|"

            } else {
                return "ERROR|Unknown operation. Use TOPK|<k>|<text> or TFIDF|<k>|<doc1>~~<doc2>~~...";
            }

        } catch (Exception e) {
            return "ERROR|Processing failed: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // TOPK handler
    // Input (after "TOPK|" prefix stripped): <k>|<text>
    // -------------------------------------------------------------------------
    static String handleTopK(String input) {
        int sep = input.indexOf('|');
        if (sep < 0) return "ERROR|Format: TOPK|<k>|<text>";

        int k;
        try {
            k = Integer.parseInt(input.substring(0, sep).trim());
        } catch (NumberFormatException e) {
            return "ERROR|<k> must be an integer";
        }
        if (k <= 0) return "ERROR|<k> must be greater than 0";

        String text = input.substring(sep + 1);
        if (text.trim().isEmpty()) return "ERROR|Text cannot be empty";

        Map<String, Integer> freq = termFrequency(text);
        if (freq.isEmpty()) return "ERROR|No meaningful terms found after filtering stop-words";

        List<Map.Entry<String, Integer>> sorted = freq.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(k)
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(":").append(e.getValue());
        }

        System.out.println("[" + nodeId + "] Top-" + k + " from " + freq.size() + " unique terms");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // TFIDF handler
    // Input (after "TFIDF|" prefix stripped): <k>|<doc1>~~<doc2>~~...
    // Documents are separated by the ~~ character (two tildes)
    // to avoid collisions with common text characters.
    // -------------------------------------------------------------------------
    static String handleTfIdf(String input) {
        int sep = input.indexOf('|');
        if (sep < 0) return "ERROR|Format: TFIDF|<k>|<doc1>~~<doc2>~~...";

        int k;
        try {
            k = Integer.parseInt(input.substring(0, sep).trim());
        } catch (NumberFormatException e) {
            return "ERROR|<k> must be an integer";
        }
        if (k <= 0) return "ERROR|<k> must be greater than 0";

        String docsRaw = input.substring(sep + 1);
        // Support both ~~ (proper section sign) and ~~ as document separators
        String[] docs = docsRaw.split("~~");

        if (docs.length < 1 || docs[0].trim().isEmpty()) {
            return "ERROR|At least one document is required";
        }

        int N = docs.length; // total number of documents

        // Compute TF for each document
        List<Map<String, Integer>> docFreqs = new ArrayList<>();
        for (String doc : docs) {
            docFreqs.add(termFrequency(doc));
        }

        Map<String, Integer> primaryTF = docFreqs.get(0);
        if (primaryTF.isEmpty()) {
            return "ERROR|No meaningful terms found in the first document after filtering stop-words";
        }

        // Compute document frequency (df) — how many docs contain each term
        Map<String, Integer> df = new HashMap<>();
        for (String term : primaryTF.keySet()) {
            int count = 0;
            for (Map<String, Integer> freq : docFreqs) {
                if (freq.containsKey(term)) count++;
            }
            df.put(term, count);
        }

        // Total terms in primary document (for normalised TF)
        int totalTerms = primaryTF.values().stream().mapToInt(Integer::intValue).sum();

        // Compute TF-IDF scores for terms in the primary document
        // TF  = count / totalTerms  (term frequency, normalised)
        // IDF = log((N + 1) / (df + 1)) + 1  (smoothed IDF, avoids division by zero)
        Map<String, Double> tfidfScores = new HashMap<>();
        for (Map.Entry<String, Integer> entry : primaryTF.entrySet()) {
            String term = entry.getKey();
            double tf   = (double) entry.getValue() / totalTerms;
            double idf  = Math.log((double)(N + 1) / (df.getOrDefault(term, 0) + 1)) + 1.0;
            tfidfScores.put(term, tf * idf);
        }

        // Sort by TF-IDF descending and take top-K
        List<Map.Entry<String, Double>> sorted = tfidfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(k)
            .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : sorted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(":").append(String.format("%.4f", e.getValue()));
        }

        System.out.println("[" + nodeId + "] TF-IDF top-" + k + " across " + N + " document(s)");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Tokenise text into lower-case alphabetic terms, filter stop-words,
    // and return a map of term -> frequency.
    // -------------------------------------------------------------------------
    static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        // Split on any non-alphabetic character sequence
        String[] tokens = text.toLowerCase().split("[^a-z]+");
        for (String token : tokens) {
            if (token.length() < 2) continue;                              // skip single characters
            if (filterStopWords && STOP_WORDS.contains(token)) continue;   // skip stop-words if enabled
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }
}