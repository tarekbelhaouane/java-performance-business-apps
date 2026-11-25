package com.bank.data;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PgWire implements AutoCloseable {
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    public long roundTrips, bytesReceived;

    public PgWire(String host, int port, String user, String database) throws IOException {
        socket = new Socket(host, port);
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
        ByteArrayOutputStream params = new ByteArrayOutputStream();
        for (String s : new String[] {"user", user, "database", database, ""}) {
            params.write(s.getBytes(StandardCharsets.UTF_8));
            params.write(0);
        }
        out.writeInt(8 + params.size());
        out.writeInt(196608);
        params.writeTo(out);
        out.flush();
        readUntilReady(null);
    }

    public List<String[]> query(String sql) throws IOException {
        byte[] q = sql.getBytes(StandardCharsets.UTF_8);
        out.writeByte('Q');
        out.writeInt(4 + q.length + 1);
        out.write(q);
        out.writeByte(0);
        out.flush();
        roundTrips++;
        List<String[]> rows = new ArrayList<>();
        readUntilReady(rows);
        return rows;
    }

    public long count(String sql) throws IOException {
        List<String[]> sink = new ArrayList<>() {
            long n;
            @Override public boolean add(String[] r) { n++; return true; }
            @Override public int size() { return (int) n; }
        };
        query0(sql, sink);
        return sink.size();
    }

    private void query0(String sql, List<String[]> rows) throws IOException {
        byte[] q = sql.getBytes(StandardCharsets.UTF_8);
        out.writeByte('Q'); out.writeInt(4 + q.length + 1); out.write(q); out.writeByte(0); out.flush();
        roundTrips++;
        readUntilReady(rows);
    }

    private void readUntilReady(List<String[]> rows) throws IOException {
        String error = null;
        while (true) {
            byte type = in.readByte();
            int len = in.readInt();
            bytesReceived += len + 1;
            switch (type) {
                case 'D' -> {
                    int n = in.readShort();
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        int l = in.readInt();
                        if (l >= 0) {
                            byte[] b = new byte[l];
                            in.readFully(b);
                            row[i] = new String(b, StandardCharsets.UTF_8);
                        }
                    }
                    if (rows != null) rows.add(row);
                }
                case 'E' -> {
                    byte[] b = new byte[len - 4];
                    in.readFully(b);
                    error = new String(b, StandardCharsets.UTF_8).replace('\0', ' ');
                }
                case 'R' -> {
                    int code = in.readInt();
                    if (code != 0) throw new IOException("authentication method " + code + " not supported");
                    in.skipBytes(len - 8);
                }
                case 'Z' -> {
                    in.skipBytes(len - 4);
                    if (error != null) throw new IOException(error);
                    return;
                }
                default -> in.skipBytes(len - 4);
            }
        }
    }

    @Override public void close() throws IOException {
        out.writeByte('X'); out.writeInt(4); out.flush();
        socket.close();
    }
}
