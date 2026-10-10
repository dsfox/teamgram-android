package org.telegram.tgnet;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A server of one's own has a key of its own, and a person gives the app the
 * server's code beside its address (ice9 #244). The app fetches the key from
 * the server and keeps it only if the code is the key's: SHA-256 over the
 * key's canonical DER, its first 10 bytes read as a big-endian number, mod
 * 10^24, written as 24 digits. Only the canonical PKCS#1 encoding of an
 * RSA-2048 key with e 65537 is taken - anything looser would let somebody vary
 * the encoding rather than the key to hit a code - and teamgram's stock key,
 * whose private half is public, never is.
 *
 * The reference is server/deploy/check-mtproto.py; this file and iOS's
 * ServerCode.swift are held to it by tests/test_the_server_code_is_one_rule.py,
 * which compiles and runs both against server/deploy/keys/server_code_vectors.json.
 * Nothing here may import more than java.* (minSdk 21 has no java.util.Base64).
 */
public final class ServerCode {
    /** The code of ice9's own key, the one built into the handshake. */
    public static final String BUILT_IN_KEY_CODE = "138485278689309020570713";
    /** The code of teamgram's stock key. */
    static final String STOCK_KEY_CODE = "021519610579788994498158";

    private static final byte[] HEAD = {0x30, (byte) 0x82, 0x01, 0x0a, 0x02, (byte) 0x82, 0x01, 0x01, 0x00};
    private static final byte[] TAIL = {0x02, 0x03, 0x01, 0x00, 0x01};
    private static final int LENGTH = 270;
    private static final String PEM_BEGIN = "-----BEGIN RSA PUBLIC KEY-----";
    private static final String PEM_END = "-----END RSA PUBLIC KEY-----";
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    private static final BigInteger TEN_TO_24 = BigInteger.TEN.pow(24);

    private ServerCode() {
    }

    public static boolean isAcceptable(byte[] der) {
        if (der == null || der.length != LENGTH || (der[9] & 0x80) == 0) {
            return false;
        }
        if (!Arrays.equals(Arrays.copyOfRange(der, 0, HEAD.length), HEAD)
                || !Arrays.equals(Arrays.copyOfRange(der, LENGTH - TAIL.length, LENGTH), TAIL)) {
            return false;
        }
        return !STOCK_KEY_CODE.equals(code(der));
    }

    /** The DER of the one RSA PUBLIC KEY block in a text, if it is a key the app may keep, else null. */
    public static byte[] derFromPem(String text) {
        if (text == null || occurrences(text, PEM_BEGIN) != 1 || occurrences(text, PEM_END) != 1) {
            return null;
        }
        int begin = text.indexOf(PEM_BEGIN) + PEM_BEGIN.length();
        int end = text.indexOf(PEM_END);
        if (begin > end) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        for (char c : text.substring(begin, end).toCharArray()) {
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                body.append(c);
            }
        }
        byte[] der = decodeBase64(body.toString());
        return der != null && isAcceptable(der) ? der : null;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    /** Exactly 360 characters of the base64 alphabet, no padding: the 270 bytes of a key. */
    private static byte[] decodeBase64(String body) {
        if (body.length() != 360) {
            return null;
        }
        byte[] out = new byte[270];
        int bits = 0, value = 0, at = 0;
        for (int i = 0; i < body.length(); i++) {
            int sextet = BASE64.indexOf(body.charAt(i));
            if (sextet < 0) {
                return null;
            }
            value = (value << 6) | sextet;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[at++] = (byte) (value >> bits);
                value &= (1 << bits) - 1;
            }
        }
        return out;
    }

    public static String code(byte[] der) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(der);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String digits = new BigInteger(1, Arrays.copyOf(digest, 10)).mod(TEN_TO_24).toString();
        StringBuilder padded = new StringBuilder();
        for (int i = digits.length(); i < 24; i++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    /** The key as PEM, rebuilt from the DER the way the built-in key is written. */
    public static String pem(byte[] der) {
        StringBuilder base64 = new StringBuilder();
        for (int i = 0; i < der.length; i += 3) {
            int chunk = (der[i] & 0xff) << 16 | (i + 1 < der.length ? (der[i + 1] & 0xff) << 8 : 0) | (i + 2 < der.length ? der[i + 2] & 0xff : 0);
            base64.append(BASE64.charAt(chunk >> 18 & 63)).append(BASE64.charAt(chunk >> 12 & 63));
            base64.append(i + 1 < der.length ? BASE64.charAt(chunk >> 6 & 63) : '=');
            base64.append(i + 2 < der.length ? BASE64.charAt(chunk & 63) : '=');
        }
        StringBuilder pem = new StringBuilder(PEM_BEGIN).append('\n');
        for (int i = 0; i < base64.length(); i += 64) {
            pem.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        return pem.append(PEM_END).toString();
    }

    /** What a person typed as a code: null if it is not one, "" if nothing. */
    public static String digits(String typed) {
        StringBuilder bare = new StringBuilder();
        for (char c : typed.toCharArray()) {
            if (c >= '0' && c <= '9') {
                bare.append(c);
            } else if (c != ' ' && c != '-') {
                return null;
            }
        }
        if (bare.length() == 0) {
            return "";
        }
        return bare.length() == 24 ? bare.toString() : null;
    }

    public static String grouped(String code) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < code.length(); i += 4) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(code, i, Math.min(i + 4, code.length()));
        }
        return out.toString();
    }

    /**
     * The link the installer prints and draws as a QR code:
     * https://i.ice9.app/server#host:port/24 digits, or tg2://server#...
     */
    public static final class Link {
        public final String host;
        public final int port;
        /** "" when the link names no code. */
        public final String code;
        /** A server link that cannot be read. */
        public final boolean malformed;

        private Link(String host, int port, String code, boolean malformed) {
            this.host = host;
            this.port = port;
            this.code = code;
            this.malformed = malformed;
        }

        private static final String[] PREFIXES = {"https://i.ice9.app/server#", "http://i.ice9.app/server#", "tg2://server#"};
        private static final Pattern REST = Pattern.compile("^([A-Za-z0-9.\\-]+):([0-9]{1,5})(?:/([0-9]{24}))?$");

        /** The server a link names, a malformed Link for a server link that cannot be read, null for any other link. */
        public static Link parse(String url) {
            if (url == null) {
                return null;
            }
            String rest = null;
            for (String prefix : PREFIXES) {
                if (url.length() >= prefix.length() && url.substring(0, prefix.length()).toLowerCase(java.util.Locale.ROOT).equals(prefix)) {
                    rest = url.substring(prefix.length());
                    break;
                }
            }
            if (rest == null) {
                return null;
            }
            Matcher match = REST.matcher(rest);
            if (rest.indexOf('%') >= 0 || !match.matches()) {
                return new Link(null, 0, null, true);
            }
            int port = Integer.parseInt(match.group(2));
            if (port < 1 || port > 65535) {
                return new Link(null, 0, null, true);
            }
            return new Link(match.group(1), port, match.group(3) != null ? match.group(3) : "", false);
        }
    }

}
