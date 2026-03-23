import java.util.Arrays;

/**
 * CSV SERVICE NODE
 *
 * Accepts comma-separated numbers and returns statistics.
 *
 * Run:
 *   javac ServiceNode.java CSVServiceNode.java
 *   java CSVServiceNode <serverIp> <myTcpPort>
 */
public class CSVServiceNode extends ServiceNode {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java CSVServiceNode <serverIp> <myTcpPort>");
            System.exit(1);
        }
        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "CSV";
        new CSVServiceNode().init();
    }

    @Override
    String processTask(String input) throws Exception {
        if (input == null || input.trim().isEmpty()) return "ERROR|No input provided";

        long start = System.currentTimeMillis();
        java.util.List<Double> nums = new java.util.ArrayList<>();
        for (String val : input.split("[,\\s\\n\\r]+")) {
            val = val.trim();
            if (val.isEmpty()) continue;
            try { nums.add(Double.parseDouble(val)); } catch (NumberFormatException ignored) {}
        }

        if (nums.isEmpty()) return "ERROR|No numeric values found";

        double[] arr = nums.stream().mapToDouble(Double::doubleValue).toArray();

        double sum = 0;
        for (double n : arr) sum += n;
        double mean = sum / arr.length;

        double min = arr[0], max = arr[0];
        for (double n : arr) { if (n < min) min = n; if (n > max) max = n; }

        double variance = 0;
        for (double n : arr) variance += Math.pow(n - mean, 2);
        double std = Math.sqrt(variance / arr.length);

        double[] sorted = arr.clone();
        Arrays.sort(sorted);
        double median = sorted.length % 2 == 0
            ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
            : sorted[sorted.length / 2];

        System.out.println("[" + nodeId + "] CSV completed in "
            + (System.currentTimeMillis() - start) + "ms | values=" + arr.length);

        return String.format("Count=%d, Mean=%.2f, Median=%.2f, STD=%.2f, Min=%.2f, Max=%.2f",
            arr.length, mean, median, std, min, max);
    }
}