import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.*;
import javax.imageio.ImageIO;

/**
 * IMAGE TRANSFORM SERVICE NODE
 *
 * Transforms images using GRAYSCALE, THUMBNAIL, ROTATE, or RESIZE.
 * Uses raw binary protocol — no Base64 encoding.
 *
 * The client sends:
 *   <operation_string>\n<raw image bytes>
 *
 * Where operation_string is one of:
 *   GRAYSCALE
 *   THUMBNAIL
 *   ROTATE:<degrees>
 *   RESIZE:<w>x<h>
 *
 * The node returns raw PNG bytes.
 *
 * Run:
 *   javac ServiceNode.java ImageServiceNode.java
 *   java ImageServiceNode <serverIp> <myTcpPort>
 */
public class ImageServiceNode extends ServiceNode {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java ImageServiceNode <serverIp> <myTcpPort>");
            System.exit(1);
        }
        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "IMAGE";
        new ImageServiceNode().init();
    }

    @Override
    boolean isBinaryService() { return true; }

    /**
     * Input bytes format: <operation>\n<raw image bytes>
     * The operation string is on the first line, image bytes follow.
     */
    @Override
    byte[] processBytes(byte[] inputBytes) throws Exception {
        // Find the newline separating operation from image bytes
        int newlinePos = -1;
        for (int i = 0; i < inputBytes.length; i++) {
            if (inputBytes[i] == '\n') { newlinePos = i; break; }
        }

        if (newlinePos < 0) return "ERROR|Missing operation line".getBytes("UTF-8");

        String operation  = new String(inputBytes, 0, newlinePos, "UTF-8").trim().toUpperCase();
        byte[] imageBytes = new byte[inputBytes.length - newlinePos - 1];
        System.arraycopy(inputBytes, newlinePos + 1, imageBytes, 0, imageBytes.length);

        System.out.println("[" + nodeId + "] Operation: " + operation
            + " on " + formatSize(imageBytes.length));

        long start = System.currentTimeMillis();
        BufferedImage img = decodeImage(imageBytes);
        BufferedImage result;

        if (operation.equals("GRAYSCALE")) {
            result = toGrayscale(img);
        } else if (operation.equals("THUMBNAIL")) {
            result = resize(img, 128, 128);
        } else if (operation.startsWith("ROTATE:")) {
            int degrees = Integer.parseInt(operation.substring(7).trim());
            result = rotate(img, degrees);
        } else if (operation.startsWith("RESIZE:")) {
            String[] wh = operation.substring(7).split("X");
            if (wh.length != 2) return "ERROR|Format: RESIZE:<w>x<h>".getBytes("UTF-8");
            result = resize(img, Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
        } else {
            return ("ERROR|Unknown operation: " + operation).getBytes("UTF-8");
        }

        byte[] output = encodeImage(result);
        System.out.println("[" + nodeId + "] " + operation + " completed in "
            + (System.currentTimeMillis() - start) + "ms"
            + " | " + img.getWidth() + "x" + img.getHeight()
            + " -> " + result.getWidth() + "x" + result.getHeight()
            + " | output=" + formatSize(output.length));
        return output;
    }

    static BufferedImage decodeImage(byte[] bytes) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IllegalArgumentException("Could not decode image");
        return img;
    }

    static byte[] encodeImage(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    static BufferedImage toGrayscale(BufferedImage original) {
        BufferedImage gray = new BufferedImage(
            original.getWidth(), original.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.drawImage(original, 0, 0, null);
        g.dispose();
        return gray;
    }

    static BufferedImage resize(BufferedImage original, int w, int h) {
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,     RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();
        return resized;
    }

    static BufferedImage rotate(BufferedImage original, int degrees) {
        double radians = Math.toRadians(degrees);
        double sin = Math.abs(Math.sin(radians)), cos = Math.abs(Math.cos(radians));
        int origW = original.getWidth(), origH = original.getHeight();
        int newW  = (int) Math.floor(origW * cos + origH * sin);
        int newH  = (int) Math.floor(origH * cos + origW * sin);
        BufferedImage rotated = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        AffineTransform at = new AffineTransform();
        at.translate((newW - origW) / 2.0, (newH - origH) / 2.0);
        at.rotate(radians, origW / 2.0, origH / 2.0);
        g.drawImage(original, at, null);
        g.dispose();
        return rotated;
    }
}