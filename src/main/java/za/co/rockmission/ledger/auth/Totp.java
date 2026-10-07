package za.co.rockmission.ledger.auth;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Time-based one-time passwords (RFC 6238, SHA-1, 6 digits, 30 s), as used by Google/Microsoft Authenticator. */
public final class Totp {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RNG = new SecureRandom();

    private Totp() {}

    public static String newSecret() {
        byte[] b = new byte[20];
        RNG.nextBytes(b);
        return encode(b);
    }

    static String encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : data) {
            buffer = (buffer << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] decode(String s) {
        String t = s.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : t.toCharArray()) {
            int v = ALPHABET.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("Not a valid base32 secret");
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
                buffer &= (1 << bits) - 1;
            }
        }
        return out.toByteArray();
    }

    static String code(String secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int off = h[h.length - 1] & 0xf;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            int mod = (int) Math.pow(10, digits);
            return String.format("%0" + digits + "d", bin % mod);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the time step the code belongs to, or -1 if it is wrong. Accepts one step either side of now
     * (clock drift) but never a step at or before lastUsedStep, so a code cannot be replayed.
     */
    public static long verify(String secret, String input, long nowSeconds, long lastUsedStep) {
        if (input == null) return -1;
        String c = input.replace(" ", "").trim();
        if (!c.matches("\\d{6}")) return -1;
        long now = nowSeconds / 30;
        for (long d = -1; d <= 1; d++) {
            long step = now + d;
            if (step <= lastUsedStep) continue;
            if (MessageDigest.isEqual(code(secret, step, 6).getBytes(StandardCharsets.UTF_8),
                                      c.getBytes(StandardCharsets.UTF_8))) {
                return step;
            }
        }
        return -1;
    }

    public static String uri(String issuer, String account, String secret) {
        String i = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20");
        String a = URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + i + ":" + a + "?secret=" + secret + "&issuer=" + i
                + "&algorithm=SHA1&digits=6&period=30";
    }
}
