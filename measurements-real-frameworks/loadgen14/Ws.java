package com.bank.connections;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.ThreadLocalRandom;

final class Ws {
    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final int TEXT = 1, CLOSE = 8, PING = 9, PONG = 10;

    record Frame(int opcode, byte[] payload) {
        String text() { return new String(payload, StandardCharsets.UTF_8); }
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != '\n') {
            if (b < 0) throw new EOFException();
            if (b != '\r') sb.append((char) b);
        }
        return sb.toString();
    }

    static String acceptKey(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String readUpgrade(InputStream in) throws IOException {
        String key = null, line;
        while (!(line = readLine(in)).isEmpty()) {
            if (line.regionMatches(true, 0, "sec-websocket-key:", 0, 18)) key = line.substring(18).trim();
        }
        if (key == null) throw new IOException("not a WebSocket upgrade");
        return key;
    }

    static void sendAccept(OutputStream out, String key) throws IOException {
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    static void clientHandshake(InputStream in, OutputStream out) throws IOException {
        byte[] k = new byte[16];
        ThreadLocalRandom.current().nextBytes(k);
        String key = Base64.getEncoder().encodeToString(k);
        out.write(("GET /quotes HTTP/1.1\r\nHost: markets.norvane.test\r\nUpgrade: websocket\r\n"
                + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine(in);
        if (!status.contains(" 101 ")) throw new IOException("handshake refused: " + status);
        while (!readLine(in).isEmpty()) {  }
    }

    static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    static Frame read(InputStream in) throws IOException {
        int b0 = readByte(in), b1 = readByte(in);
        long len = b1 & 0x7F;
        if (len == 126) len = (readByte(in) << 8) | readByte(in);
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in); }
        byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
        byte[] p = in.readNBytes((int) len);
        if (p.length < len || (mask != null && mask.length < 4)) throw new EOFException();
        if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
        return new Frame(b0 & 0x0F, p);
    }

    static byte[] encode(int opcode, byte[] payload, boolean masked) {
        int n = payload.length;
        int head = 2 + (n < 126 ? 0 : n < 65536 ? 2 : 8) + (masked ? 4 : 0);
        byte[] f = new byte[head + n];
        f[0] = (byte) (0x80 | opcode);
        int i = 2;
        if (n < 126) f[1] = (byte) n;
        else if (n < 65536) { f[1] = 126; f[2] = (byte) (n >> 8); f[3] = (byte) n; i = 4; }
        else { f[1] = 127; for (int j = 0; j < 8; j++) f[2 + j] = (byte) ((long) n >> (56 - 8 * j)); i = 10; }
        if (masked) {
            f[1] |= (byte) 0x80;
            byte[] m = new byte[4];
            ThreadLocalRandom.current().nextBytes(m);
            System.arraycopy(m, 0, f, i, 4);
            i += 4;
            for (int j = 0; j < n; j++) f[i + j] = (byte) (payload[j] ^ m[j & 3]);
        } else System.arraycopy(payload, 0, f, i, n);
        return f;
    }

    static byte[] text(String s, boolean masked) {
        return encode(TEXT, s.getBytes(StandardCharsets.UTF_8), masked);
    }
}
