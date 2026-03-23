import java.util.*;
import java.util.stream.Collectors;

/**
 * TOP-K TERMS / TF-IDF SERVICE NODE
 *
 * Registration name: TOPK
 *
 * Supported operations (the full input string, exactly as the client types it):
 *
 *   TOPK|<k>|<text>
 *       → Returns the top-K most frequent terms in <text>, sorted by frequency.
 *         Common English stop-words are filtered out (unless filter=off).
 *         Returns: term1:count1, term2:count2, ...
 *
 *   TFIDF|<k>|<doc1>~~<doc2>~~...
 *       → Computes TF-IDF scores across multiple documents separated by ~~.
 *         Returns top-K terms from the first document by TF-IDF score.
 *         Returns: term1:score1, term2:score2, ...
 *
 *   For file input, the client reads the file(s) and sends their text content
 *   in the above formats. For TFIDF with multiple files, the client joins them
 *   with ~~ before sending, so the node always sees the same wire format.
 *
 * Run:
 *   javac *.java
 *   java TopKServiceNode <serverIp> <myTcpPort> [filter=on|off]
 *
 * Example:
 *   java TopKServiceNode 127.0.0.1 9105
 *   java TopKServiceNode 127.0.0.1 9105 filter=off
 */
public class TopKServiceNode extends ServiceNode {

    // Minimum token length — 4 filters PDF encoding artifacts (pk, cb, sr, etc.)
    static final int MIN_TOKEN_LENGTH = 4;

    static boolean filterStopWords = true;

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
            System.out.println("Example: java TopKServiceNode 127.0.0.1 9105");
            System.out.println("         java TopKServiceNode 127.0.0.1 9105 filter=off");
            System.exit(1);
        }

        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "TOPK";

        if (args.length >= 3) {
            String flag = args[2].trim().toLowerCase();
            if      (flag.equals("filter=off")) filterStopWords = false;
            else if (flag.equals("filter=on"))  filterStopWords = true;
            else System.out.println("[TopK] Unknown arg '" + args[2] + "', defaulting to filter=on");
        }

        new TopKServiceNode().init();
    }

    // -------------------------------------------------------------------------
    // processTask receives the full input string exactly as the client sent it.
    //
    // Expected formats:
    //   TOPK|5|The quick brown fox...
    //   TFIDF|3|document one text~~document two text~~document three text
    //
    // For file input the client reads the file(s) and sends their text content
    // in these formats — the node never sees file paths, only text content.
    // -------------------------------------------------------------------------
    @Override
    String processTask(String input) throws Exception {
        String upper = input.toUpperCase();

        if (upper.startsWith("TOPK|")) {
            return handleTopK(input.substring(5));   // strip "TOPK|"
        } else if (upper.startsWith("TFIDF|")) {
            return handleTfIdf(input.substring(6));  // strip "TFIDF|"
        } else {
            return "ERROR|Unknown operation. Use TOPK|<k>|<text> or TFIDF|<k>|<doc1>~~<doc2>~~...";
        }
    }

    // -------------------------------------------------------------------------
    // TOPK handler — input (after "TOPK|" stripped): <k>|<text>
    // -------------------------------------------------------------------------
    static String handleTopK(String input) {
        long start = System.currentTimeMillis();
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

        System.out.println("[" + nodeId + "] TOPK completed in "
            + (System.currentTimeMillis() - start) + "ms | k=" + k + " | unique terms=" + freq.size());
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // TFIDF handler — input (after "TFIDF|" stripped): <k>|<doc1>~~<doc2>~~...
    // Documents are separated by ~~ (two tildes).
    // The client joins multiple file contents with ~~ before sending.
    // -------------------------------------------------------------------------
    static String handleTfIdf(String input) {
        long start = System.currentTimeMillis();
        int sep = input.indexOf('|');
        if (sep < 0) return "ERROR|Format: TFIDF|<k>|<doc1>~~<doc2>~~...";

        int k;
        try {
            k = Integer.parseInt(input.substring(0, sep).trim());
        } catch (NumberFormatException e) {
            return "ERROR|<k> must be an integer";
        }
        if (k <= 0) return "ERROR|<k> must be greater than 0";

        String   docsRaw = input.substring(sep + 1);
        String[] docs    = docsRaw.split("~~");

        if (docs.length < 1 || docs[0].trim().isEmpty()) {
            return "ERROR|At least one document is required";
        }

        int N = docs.length;

        List<Map<String, Integer>> docFreqs = new ArrayList<>();
        for (String doc : docs) {
            docFreqs.add(termFrequency(doc));
        }

        Map<String, Integer> primaryTF = docFreqs.get(0);
        if (primaryTF.isEmpty()) {
            return "ERROR|No meaningful terms found in the first document after filtering stop-words";
        }

        // Document frequency: how many docs contain each term from the primary doc
        Map<String, Integer> df = new HashMap<>();
        for (String term : primaryTF.keySet()) {
            int count = 0;
            for (Map<String, Integer> freq : docFreqs) {
                if (freq.containsKey(term)) count++;
            }
            df.put(term, count);
        }

        int totalTerms = primaryTF.values().stream().mapToInt(Integer::intValue).sum();

        // TF  = count / totalTerms
        // IDF = log((N+1) / (df+1)) + 1  (smoothed, avoids zero)
        Map<String, Double> tfidfScores = new HashMap<>();
        for (Map.Entry<String, Integer> entry : primaryTF.entrySet()) {
            String term = entry.getKey();
            double tf   = (double) entry.getValue() / totalTerms;
            double idf  = Math.log((double)(N + 1) / (df.getOrDefault(term, 0) + 1)) + 1.0;
            tfidfScores.put(term, tf * idf);
        }

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

        System.out.println("[" + nodeId + "] TFIDF completed in "
            + (System.currentTimeMillis() - start) + "ms | k=" + k + " | docs=" + N);
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Tokenise text into lower-case alphabetic terms, filter stop-words,
    // and return a map of term -> frequency.
    //
    // MIN_TOKEN_LENGTH=4 filters PDF encoding artifacts (pk, cb, sr, ni, etc.)
    // -------------------------------------------------------------------------
    static Map<String, Integer> termFrequency(String text) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        String[] tokens = text.toLowerCase().split("[^a-z]+");
        for (String token : tokens) {
            if (token.length() < MIN_TOKEN_LENGTH) continue;
            if (filterStopWords && STOP_WORDS.contains(token)) continue;
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }
}