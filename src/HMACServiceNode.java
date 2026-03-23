import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC SERVICE NODE
 *
 * Signs or verifies messages using HMAC-SHA256.
 *
 * Input formats:
 *   SIGN|<key>|<message>                    → returns Base64 signature
 *   VERIFY|<key>|<message>|<signature>      → returns VALID or INVALID
 *
 * Run:
 *   javac ServiceNode.java HMACServiceNode.java
 *   java HMACServiceNode <serverIp> <myTcpPort>
 */
public class HMACServiceNode extends ServiceNode {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java HMACServiceNode <serverIp> <myTcpPort>");
            System.exit(1);
        }
        serverIp    = args[0];
        myTcpPort   = Integer.parseInt(args[1]);
        serviceName = "HMAC";
        new HMACServiceNode().init();
    }

    @Override
    String processTask(String input) throws Exception {
        if (input.toUpperCase().startsWith("SIGN|")) {
            String body = input.substring(5);
            int sep = body.indexOf('|');
            if (sep < 0) return "ERROR|Format: SIGN|<key>|<message>";
            String key = body.substring(0, sep);
            String msg = body.substring(sep + 1);
            if (key.isEmpty()) return "ERROR|Key cannot be empty";
            if (msg.isEmpty())  return "ERROR|Message cannot be empty";
            return Base64.getEncoder().encodeToString(computeHmac(key, msg));

        } else if (input.toUpperCase().startsWith("VERIFY|")) {
            String body     = input.substring(7);
            int    firstSep = body.indexOf('|');
            if (firstSep < 0) return "ERROR|Format: VERIFY|<key>|<message>|<signature>";
            String key      = body.substring(0, firstSep);
            String rest     = body.substring(firstSep + 1);
            int    lastSep  = rest.lastIndexOf('|');
            if (lastSep < 0) return "ERROR|Format: VERIFY|<key>|<message>|<signature>";
            String message   = rest.substring(0, lastSep);
            String signature = rest.substring(lastSep + 1);
            if (key.isEmpty())       return "ERROR|Key cannot be empty";
            if (message.isEmpty())   return "ERROR|Message cannot be empty";
            if (signature.isEmpty()) return "ERROR|Signature cannot be empty";
            byte[] expected = computeHmac(key, message);
            byte[] provided = Base64.getDecoder().decode(signature);
            return MessageDigest.isEqual(expected, provided) ? "VALID" : "INVALID";

        } else {
            return "ERROR|Use SIGN|<key>|<message> or VERIFY|<key>|<message>|<signature>";
        }
    }

    static byte[] computeHmac(String key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }
}