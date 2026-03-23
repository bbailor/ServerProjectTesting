import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * COMPRESSION SERVICE NODE
 *
 * Compresses or decompresses data using GZIP.
 * Uses raw binary protocol — no Base64 encoding at all.
 *
 * The client sends one byte as the operation flag followed by the raw data:
 *   'C' (byte 67) = COMPRESS   → returns GZIP compressed bytes
 *   'D' (byte 68) = DECOMPRESS → returns decompressed bytes
 *
 * Run:
 *   javac ServiceNode.java CompressionServiceNode.java
 *   java CompressionServiceNode <serverIp> <myTcpPort>
 */
public class CompressionServiceNode extends ServiceNode {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java CompressionServiceNode <serverIp> <myTcpPort>");
            System.exit(1);
        }
        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "COMPRESSION";
        new CompressionServiceNode().init();
    }

    @Override
    boolean isBinaryService() { return true; }

    @Override
    byte[] processBytes(byte[] inputBytes) throws Exception {
        if (inputBytes.length < 1) return "ERROR|Empty input".getBytes("UTF-8");

        char   operation = (char) inputBytes[0];
        byte[] data      = new byte[inputBytes.length - 1];
        System.arraycopy(inputBytes, 1, data, 0, data.length);

        System.out.println("[" + nodeId + "] Operation: "
            + (operation == 'C' ? "COMPRESS" : "DECOMPRESS")
            + " on " + formatSize(data.length));

        if (operation == 'C')      return compress(data);
        else if (operation == 'D') return decompress(data);
        else return ("ERROR|Unknown operation: " + operation).getBytes("UTF-8");
    }

    static byte[] compress(byte[] input) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos, 64 * 1024)) {
            gzip.write(input);
        }
        System.out.println("[" + nodeId + "] Compressed: "
            + formatSize(input.length) + " -> " + formatSize(baos.size()));
        return baos.toByteArray();
    }

    static byte[] decompress(byte[] input) throws Exception {
        ByteArrayInputStream  bais = new ByteArrayInputStream(input);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(bais, 64 * 1024)) {
            byte[] buf = new byte[64 * 1024];
            int    len;
            while ((len = gzip.read(buf)) != -1) baos.write(buf, 0, len);
        }
        System.out.println("[" + nodeId + "] Decompressed: "
            + formatSize(input.length) + " -> " + formatSize(baos.size()));
        return baos.toByteArray();
    }
}