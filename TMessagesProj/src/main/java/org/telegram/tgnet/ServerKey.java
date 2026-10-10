package org.telegram.tgnet;

import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Fetches a server's own key the way check-mtproto.py --code does (ice9 #244):
 * a plain TCP connection to the MTProto port, {@code GET /key}, and the RSA
 * PUBLIC KEY block in the answer, read strictly by {@link ServerCode}. The only
 * thing trusted about the answer is the code it is checked against.
 *
 * Off the main thread only. The twin of ServerKey.swift on the other client.
 */
public final class ServerKey {

    public enum Kind {
        /** A key the app may keep - whether it is the right one is the code's to say. */
        KEY,
        /** Something answered, but gave no key the app takes. */
        NO_KEY,
        /** No connection, or nothing at all before the time was up. */
        UNREACHABLE
    }

    public static final class Answer {
        public final Kind kind;
        public final byte[] der;

        private Answer(Kind kind, byte[] der) {
            this.kind = kind;
            this.der = der;
        }
    }

    private static final int TIMEOUT = 10000;
    private static final int LIMIT = 8192;

    private ServerKey() {
    }

    /** dialable is what the socket opens, host what the request names. */
    public static Answer fetch(String dialable, String host, int port) {
        Answer answer = fetchNow(dialable, host, port);
        FileLog.d("ice9 GET /key at " + host + ":" + port + ": " + (answer.kind == Kind.KEY
                ? "a key with the code " + ServerCode.code(answer.der)
                : answer.kind == Kind.NO_KEY ? "no key the app takes" : "nothing answered"));
        return answer;
    }

    private static Answer fetchNow(String dialable, String host, int port) {
        long deadline = System.currentTimeMillis() + TIMEOUT;
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(dialable, port), TIMEOUT);
            OutputStream out = socket.getOutputStream();
            out.write(("GET /key HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[4096];
            while (received.size() < LIMIT) {
                int left = (int) (deadline - System.currentTimeMillis());
                if (left <= 0) {
                    break;
                }
                socket.setSoTimeout(left);
                int read = in.read(buffer, 0, Math.min(buffer.length, LIMIT - received.size()));
                if (read <= 0) {
                    break;
                }
                received.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            if (received.size() == 0) {
                return new Answer(Kind.UNREACHABLE, null);
            }
        }
        if (received.size() == 0) {
            return new Answer(Kind.UNREACHABLE, null);
        }
        byte[] der = ServerCode.derFromPem(new String(received.toByteArray(), StandardCharsets.US_ASCII));
        return der == null ? new Answer(Kind.NO_KEY, null) : new Answer(Kind.KEY, der);
    }
}
