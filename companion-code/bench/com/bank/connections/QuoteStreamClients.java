package com.bank.connections;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class QuoteStreamClients {
    static String host = "127.0.0.1";
    static int port;
    static final String[] SYMBOLS = QuoteStreamServer.SYMBOLS;

    static final class Conn implements Closeable {
        final Socket s; final InputStream in; final OutputStream out;
        Conn(int rcvbuf, int timeoutMs) throws IOException {
            s = new Socket();
            try {
                if (rcvbuf > 0) s.setReceiveBufferSize(rcvbuf);
                s.connect(new java.net.InetSocketAddress(host, port), 5000);
                s.setTcpNoDelay(true);
                if (timeoutMs > 0) s.setSoTimeout(timeoutMs);
                in = new BufferedInputStream(s.getInputStream());
                out = s.getOutputStream();
                Ws.clientHandshake(in, out);
            } catch (IOException e) {
                s.close();
                throw e;
            }
        }
        void send(String text) throws IOException { out.write(Ws.text(text, true)); out.flush(); }
        void pong(byte[] p) throws IOException { out.write(Ws.encode(Ws.PONG, p, true)); out.flush(); }
        public void close() { try { s.close(); } catch (IOException ignored) { } }
    }

    public static void main(String[] args) throws Exception {
        port = Integer.parseInt(args[1]);
        switch (args[0]) {
            case "churn" -> churn(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            case "watch" -> watch(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "frozen" -> frozen(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            case "silent" -> hold(Integer.parseInt(args[2]), false, 0);
            case "idle" -> hold(Integer.parseInt(args[2]), true, Integer.parseInt(args[3]));
            case "storm" -> storm(Integer.parseInt(args[2]), Integer.parseInt(args[3]), args[4],
                    Integer.parseInt(args[5]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void churn(int total, int concurrency, int lifeMs, int abruptPct) throws Exception {
        AtomicInteger started = new AtomicInteger(), done = new AtomicInteger(), abrupt = new AtomicInteger();
        long t0 = System.nanoTime();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < concurrency; w++) ex.submit(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                while (started.incrementAndGet() <= total) {
                    try (Conn c = new Conn(0, 50)) {
                        List<String> mine = new ArrayList<>(List.of(SYMBOLS));
                        Collections.shuffle(mine);
                        mine = mine.subList(0, 5);
                        for (String s : mine) c.send("SUB " + s);
                        long end = System.nanoTime() + lifeMs * 1_000_000L;
                        while (System.nanoTime() < end) {
                            try {
                                Ws.Frame f = Ws.read(c.in);
                                if (f.opcode() == Ws.PING) c.pong(f.payload());
                            } catch (SocketTimeoutException e) {  }
                        }
                        if (r.nextInt(100) < abruptPct) {
                            abrupt.incrementAndGet();
                        } else {
                            for (String s : mine) c.send("UNSUB " + s);
                            c.out.write(Ws.encode(Ws.CLOSE, new byte[0], true));
                            c.out.flush();
                        }
                    } catch (IOException e) {  }
                    int d = done.incrementAndGet();
                    if (d % 5000 == 0) System.out.printf("%6d sessions ended (%d abrupt) in %d s%n",
                            d, abrupt.get(), (System.nanoTime() - t0) / 1_000_000_000L);
                }
                return null;
            });
        }
        System.out.printf("churn done: %d sessions, %d abrupt%n", done.get(), abrupt.get());
    }

    static void watch(int clients, int seconds) throws Exception {
        final long[][] buf = {new long[2_000_000]};
        final int[] n = {0};
        Object lock = new Object();
        for (int i = 0; i < clients; i++) {
            Thread.ofVirtual().start(() -> {
                try (Conn c = new Conn(0, 0)) {
                    List<String> mine = new ArrayList<>(List.of(SYMBOLS));
                    Collections.shuffle(mine);
                    for (String s : mine.subList(0, 5)) c.send("SUB " + s);
                    while (true) {
                        Ws.Frame f = Ws.read(c.in);
                        if (f.opcode() == Ws.PING) { c.pong(f.payload()); continue; }
                        String t = f.text();
                        int k = t.indexOf("\"ts\":") + 5;
                        long ts = Long.parseLong(t, k, t.indexOf(',', k), 10);
                        long lat = System.nanoTime() - ts;
                        synchronized (lock) { if (n[0] < buf[0].length) buf[0][n[0]++] = lat; }
                    }
                } catch (IOException e) { System.out.println("watcher lost: " + e); }
            });
        }
        for (int s = 1; s <= seconds; s++) {
            Thread.sleep(1000);
            long[] v;
            synchronized (lock) { v = Arrays.copyOf(buf[0], n[0]); n[0] = 0; }
            Arrays.sort(v);
            System.out.printf("t=%3ds messages/s %6d  p50 %7.1f ms  p99 %8.1f ms  max %8.1f ms%n", s, v.length,
                    v.length == 0 ? 0 : v[v.length / 2] / 1e6, v.length == 0 ? 0 : v[(int) (v.length * 0.99)] / 1e6,
                    v.length == 0 ? 0 : v[v.length - 1] / 1e6);
        }
        System.exit(0);
    }

    static void frozen(int clients, int delaySec) throws Exception {
        Thread.sleep(delaySec * 1000L);
        List<Conn> keep = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            Conn c = new Conn(4096, 0);
            for (String s : SYMBOLS) c.send("SUB " + s);
            keep.add(c);
        }
        System.out.println(clients + " frozen client(s) subscribed to 10 pairs; not reading");
        Thread.sleep(Long.MAX_VALUE);
    }

    static final List<Conn> KEEP = new ArrayList<>();

    static void hold(int clients, boolean answerPings, int seconds) throws Exception {
        for (int i = 0; i < clients; i++) {
            Conn c = new Conn(0, 0);
            KEEP.add(c);
            if (answerPings) Thread.ofVirtual().start(() -> {
                try { while (true) { Ws.Frame f = Ws.read(c.in); if (f.opcode() == Ws.PING) c.pong(f.payload()); } }
                catch (IOException e) { System.out.println("idle client lost: " + e); }
            });
        }
        System.out.println(clients + (answerPings ? " idle clients answering pings" : " silent clients"));
        Thread.sleep(seconds > 0 ? seconds * 1000L : Long.MAX_VALUE);
        System.exit(0);
    }

    static void storm(int clients, int timeoutMs, String strategy, int spreadMs) throws Exception {
        long t0 = System.nanoTime();
        long[] connectedAt = new long[clients];
        AtomicLong attempts = new AtomicLong();
        java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
        Thread.ofPlatform().daemon().start(() -> {
            for (int sec = 5; ; sec += 5) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                System.out.printf("t=%3ds connected %5d of %d, attempts %6d%n", sec, ok.get(), clients, attempts.get());
            }
        });
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < clients; i++) {
                int id = i;
                ex.submit(() -> {
                    ThreadLocalRandom r = ThreadLocalRandom.current();
                    if (strategy.equals("jitter")) Thread.sleep(r.nextInt(spreadMs + 1));
                    for (int a = 0; ; a++) {
                        attempts.incrementAndGet();
                        try (Conn c = new Conn(0, timeoutMs)) {
                            connectedAt[id] = System.nanoTime() - t0;
                            ok.incrementAndGet();
                            return null;
                        } catch (IOException e) {
                            if (strategy.equals("jitter"))
                                Thread.sleep(r.nextLong(Math.min(30_000, 1_000L << Math.min(a, 5)) + 1));
                        }
                    }
                });
            }
        }
        long[] v = connectedAt.clone();
        Arrays.sort(v);
        System.out.printf(Locale.ROOT, "%s: %d clients, %d attempts; connected p50 %.1f s, p99 %.1f s, last %.1f s%n",
                strategy, clients, attempts.get(), v[clients / 2] / 1e9, v[(int) (clients * 0.99)] / 1e9,
                v[clients - 1] / 1e9);
    }
}
