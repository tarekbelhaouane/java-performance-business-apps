package com.bank.connections;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class QuoteStreamServer {
    static final boolean FIXED = System.getProperty("mode", "fixed").equals("fixed");
    static final String DELIVERY = System.getProperty("delivery", "bounded");
    static final int QUEUE = Integer.getInteger("queue", 1000);
    static final int HEARTBEAT = Integer.getInteger("heartbeat", 0);
    static final int TICK = Integer.getInteger("tick", 20);
    static final int AUTH_MS = Integer.getInteger("authMs", 0);
    static final Semaphore AUTH = new Semaphore(Integer.getInteger("authPermits", 10), true);
    static final boolean GC = Boolean.getBoolean("gc");
    static final String[] SYMBOLS = {"EURUSD", "GBPUSD", "USDJPY", "USDCHF", "EURGBP",
                                     "AUDUSD", "USDCAD", "NZDUSD", "EURJPY", "EURCHF"};
    static final AtomicLong IDS = new AtomicLong();

    static final class Session {
        final long id = IDS.incrementAndGet();
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final Set<String> symbols = ConcurrentHashMap.newKeySet();
        final BlockingQueue<byte[]> queue;
        volatile long lastSeen = System.nanoTime();
        volatile boolean open = true;

        Session(Socket s) throws IOException {
            socket = s;
            in = new BufferedInputStream(s.getInputStream());
            out = new BufferedOutputStream(s.getOutputStream());
            queue = switch (DELIVERY) {
                case "queue" -> new LinkedBlockingQueue<>();
                case "bounded" -> new ArrayBlockingQueue<>(QUEUE);
                default -> null;
            };
        }
        synchronized void write(byte[] frame) throws IOException { out.write(frame); out.flush(); }
        void close() { open = false; try { socket.close(); } catch (IOException ignored) { } }
    }

    static final Map<String, Set<Session>> BY_SYMBOL = new ConcurrentHashMap<>();
    static final Set<Session> CONNECTED = ConcurrentHashMap.newKeySet();
    static final LongAdder FRAMES = new LongAdder(), SEND_FAILED = new LongAdder(), SLOW_CLOSED = new LongAdder(),
            HB_CLOSED = new LongAdder(), OPENED = new LongAdder(), CLEAN = new LongAdder(), ABRUPT = new LongAdder(),
            HANDSHAKES_WASTED = new LongAdder();
    static volatile long maxTickMicros;

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int report = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        ThreadFactory tf = System.getProperty("threads", "virtual").equals("virtual")
                ? Thread.ofVirtual().name("ws-", 0).factory() : Thread.ofPlatform().name("ws-", 0).factory();
        Thread.ofPlatform().daemon().name("broadcaster").start(QuoteStreamServer::broadcast);
        if (HEARTBEAT > 0) Thread.ofPlatform().daemon().name("heartbeat").start(QuoteStreamServer::heartbeat);
        Thread.ofPlatform().daemon().name("report").start(() -> report(report));
        System.out.printf("quote stream on %d: mode=%s threads=%s delivery=%s queue=%d heartbeat=%ds authMs=%d%n",
                port, FIXED ? "fixed" : "leak", System.getProperty("threads", "virtual"), DELIVERY, QUEUE,
                HEARTBEAT, AUTH_MS);
        try (ServerSocket ss = new ServerSocket(port, 4096)) {
            while (true) {
                Socket s = ss.accept();
                tf.newThread(() -> handle(s)).start();
            }
        }
    }

    static void handle(Socket s) {
        Session x = null;
        try {
            s.setTcpNoDelay(true);
            x = new Session(s);
            String key = Ws.readUpgrade(x.in);
            if (AUTH_MS > 0) {
                AUTH.acquire();
                try { Thread.sleep(AUTH_MS); } finally { AUTH.release(); }
            }
            try { Ws.sendAccept(x.out, key); } catch (IOException e) { HANDSHAKES_WASTED.increment(); throw e; }
            CONNECTED.add(x);
            OPENED.increment();
            if (x.queue != null) { Session w = x; Thread.ofVirtual().start(() -> writer(w)); }
            while (true) {
                Ws.Frame f = Ws.read(x.in);
                x.lastSeen = System.nanoTime();
                switch (f.opcode()) {
                    case Ws.TEXT -> onMessage(x, f.text());
                    case Ws.PING -> x.write(Ws.encode(Ws.PONG, f.payload(), false));
                    case Ws.CLOSE -> {
                        try { x.write(Ws.encode(Ws.CLOSE, new byte[0], false)); } catch (IOException ignored) { }
                        CLEAN.increment();
                        return;
                    }
                    default -> { }
                }
            }
        } catch (IOException | InterruptedException e) {
            if (x != null) ABRUPT.increment();
        } finally {
            if (x != null) {
                x.close();
                CONNECTED.remove(x);
                if (FIXED) unsubscribeAll(x);
            } else try { s.close(); } catch (IOException ignored) { }
        }
    }

    static void onMessage(Session x, String m) {
        if (m.startsWith("SUB ")) {
            String sym = m.substring(4);
            x.symbols.add(sym);
            BY_SYMBOL.computeIfAbsent(sym, k -> ConcurrentHashMap.newKeySet()).add(x);
        } else if (m.startsWith("UNSUB ")) {
            String sym = m.substring(6);
            x.symbols.remove(sym);
            Set<Session> subs = BY_SYMBOL.get(sym);
            if (subs != null) subs.remove(x);
        }
    }

    static void unsubscribeAll(Session x) {
        for (String sym : x.symbols) {
            Set<Session> subs = BY_SYMBOL.get(sym);
            if (subs != null) subs.remove(x);
        }
        x.symbols.clear();
    }

    static void writer(Session x) {
        try {
            while (x.open) {
                byte[] f = x.queue.poll(1, TimeUnit.SECONDS);
                if (f != null) { x.write(f); FRAMES.increment(); }
            }
        } catch (IOException | InterruptedException e) {
            SEND_FAILED.increment();
            x.close();
        }
    }

    static void deliver(Session x, byte[] frame) {
        switch (DELIVERY) {
            case "sync" -> {
                try { x.write(frame); FRAMES.increment(); }
                catch (IOException e) { SEND_FAILED.increment(); x.close(); }
            }
            case "queue" -> x.queue.offer(frame);
            default -> {
                if (!x.queue.offer(frame)) { SLOW_CLOSED.increment(); x.close(); }
            }
        }
    }

    static void broadcast() {
        Random rnd = new Random(42);
        double[] mid = new double[SYMBOLS.length];
        Arrays.fill(mid, 1.08);
        long next = System.nanoTime();
        while (true) {
            long t0 = System.nanoTime();
            for (int i = 0; i < SYMBOLS.length; i++) {
                Set<Session> subs = BY_SYMBOL.get(SYMBOLS[i]);
                if (subs == null || subs.isEmpty()) continue;
                mid[i] += (rnd.nextDouble() - 0.5) * 1e-4;
                StringBuilder book = new StringBuilder(640);
                for (int l = 1; l <= 10; l++)
                    book.append(String.format(Locale.ROOT, "[%.5f,%d,%.5f,%d],",
                            mid[i] - l * 1e-5, l * 1_000_000, mid[i] + l * 1e-5, l * 1_000_000));
                String body = book.substring(0, book.length() - 1);
                for (Session x : subs) {
                    if (!x.open) continue;
                    deliver(x, Ws.text("{\"sub\":\"" + x.id + "-" + SYMBOLS[i] + "\",\"s\":\"" + SYMBOLS[i]
                            + "\",\"ts\":" + t0 + ",\"book\":[" + body + "]}", false));
                }
            }
            long micros = (System.nanoTime() - t0) / 1000;
            if (micros > maxTickMicros) maxTickMicros = micros;
            next += TICK * 1_000_000L;
            long sleep = next - System.nanoTime();
            if (sleep > 0) LockSupport_park(sleep); else next = System.nanoTime();
        }
    }
    static void LockSupport_park(long nanos) { java.util.concurrent.locks.LockSupport.parkNanos(nanos); }

    static void heartbeat() {
        byte[] ping = Ws.encode(Ws.PING, "hb".getBytes(), false);
        while (true) {
            try { Thread.sleep(HEARTBEAT * 1000L); } catch (InterruptedException e) { return; }
            long now = System.nanoTime();
            for (Session x : CONNECTED) {
                if (now - x.lastSeen > 3L * HEARTBEAT * 1_000_000_000L) { HB_CLOSED.increment(); x.close(); }
                else if (x.queue == null) { try { x.write(ping); } catch (IOException e) { x.close(); } }
                else x.queue.offer(ping);
            }
        }
    }

    static long rssMb() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        } catch (IOException ignored) { }
        return -1;
    }

    static void report(int seconds) {
        var mem = ManagementFactory.getMemoryMXBean();
        var thr = ManagementFactory.getThreadMXBean();
        long start = System.nanoTime(), lastFrames = 0;
        while (true) {
            try { Thread.sleep(seconds * 1000L); } catch (InterruptedException e) { return; }
            if (GC) System.gc();
            Set<Session> registered = new HashSet<>();
            long queued = 0;
            for (Set<Session> subs : BY_SYMBOL.values()) registered.addAll(subs);
            for (Session x : registered) if (x.queue != null) queued += x.queue.size();
            long f = FRAMES.sum();
            String[] fd = new File("/proc/self/fd").list();
            System.out.printf(Locale.ROOT,
                    "t=%3ds connected=%5d registered=%6d (closed but registered %6d) opened=%6d clean=%6d abrupt=%6d"
                    + " | heap %s %4d MB rss %4d MB threads %5d fds %5d | frames/s %6d queued %7d"
                    + " slowClosed %d hbClosed %d sendFailed %d wastedHandshakes %d maxTick %d ms%n",
                    (System.nanoTime() - start) / 1_000_000_000L, CONNECTED.size(), registered.size(),
                    registered.stream().filter(x -> !x.open).count(), OPENED.sum(), CLEAN.sum(), ABRUPT.sum(),
                    GC ? "live" : "used", mem.getHeapMemoryUsage().getUsed() >> 20, rssMb(), thr.getThreadCount(),
                    fd == null ? -1 : fd.length, (f - lastFrames) / seconds, queued, SLOW_CLOSED.sum(),
                    HB_CLOSED.sum(), SEND_FAILED.sum(), HANDSHAKES_WASTED.sum(), maxTickMicros / 1000);
            lastFrames = f;
            maxTickMicros = 0;
        }
    }
}
