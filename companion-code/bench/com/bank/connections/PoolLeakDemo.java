package com.bank.connections;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class PoolLeakDemo {
    static final int MAX = 20;

    static final class Conn {
        final Socket s; final InputStream in; final OutputStream out;
        Conn(int port) throws IOException {
            s = new Socket("127.0.0.1", port); s.setSoTimeout(2000); s.setTcpNoDelay(true);
            in = new BufferedInputStream(s.getInputStream()); out = s.getOutputStream();
        }
    }

    static final class ConnectionPool {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition released = lock.newCondition();
        private final ArrayDeque<Conn> idle = new ArrayDeque<>();
        private final int port; private int created; int leased; int waiting;
        ConnectionPool(int port) { this.port = port; }

        Conn lease(long timeoutMs) throws Exception {
            lock.lock();
            try {
                long nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                while (idle.isEmpty() && created == MAX) {
                    waiting++;
                    try {
                        if (timeoutMs <= 0) released.await();
                        else if ((nanos = released.awaitNanos(nanos)) <= 0)
                            throw new TimeoutException("Timeout waiting for connection from pool");
                    } finally { waiting--; }
                }
                Conn c = idle.pollFirst();
                if (c == null) { c = new Conn(port); created++; }
                leased++;
                return c;
            } finally { lock.unlock(); }
        }

        void release(Conn c) {
            lock.lock();
            try { leased--; idle.addFirst(c); released.signal(); } finally { lock.unlock(); }
        }
    }

    static int[] send(Conn c, long id) throws IOException {
        c.out.write(("GET /statements/" + id + " HTTP/1.1\r\nHost: partner\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        c.out.flush();
        int status = 0, length = 0; String line;
        while (!(line = readLine(c.in)).isEmpty()) {
            if (line.startsWith("HTTP/")) status = Integer.parseInt(line.substring(9, 12));
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        }
        return new int[] {status, length};
    }
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(); int b;
        while ((b = in.read()) != '\n') { if (b < 0) throw new EOFException(); if (b != '\r') sb.append((char) b); }
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        int threads = Integer.parseInt(args[2]);
        int seconds = Integer.parseInt(args[3]);
        long leaseTimeout = mode.equals("leak-timeout") ? 2000 : 0;
        ConnectionPool pool = new ConnectionPool(port);
        AtomicLong done = new AtomicLong(), notFound = new AtomicLong(), poolTimeouts = new AtomicLong(), ids = new AtomicLong();
        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                while (true) {
                    try {
                        Conn c = pool.lease(leaseTimeout);
                        if (mode.equals("fixed")) {
                            try {
                                int[] r = send(c, ids.incrementAndGet());
                                c.in.readNBytes(r[1]);
                                if (r[0] != 200) notFound.incrementAndGet();
                            } finally { pool.release(c); }
                        } else {
                            int[] r = send(c, ids.incrementAndGet());
                            if (r[0] != 200) { notFound.incrementAndGet(); continue; }
                            c.in.readNBytes(r[1]);
                            pool.release(c);
                        }
                        done.incrementAndGet();
                        Thread.sleep(20);
                    } catch (TimeoutException e) { poolTimeouts.incrementAndGet();
                    } catch (Exception e) { System.out.println(e); return; }
                }
            }, "http-request-" + t);
            th.setDaemon(true); th.start();
        }
        long last = 0;
        for (int s = 1; s <= seconds; s++) {
            Thread.sleep(1000);
            long d = done.get();
            System.out.printf("t=%2ds  requests/s=%4d  404 so far=%3d  leased=%2d/%d  threads waiting for a connection=%2d  pool timeouts=%d%n",
                    s, d - last, notFound.get(), pool.leased, MAX, pool.waiting, poolTimeouts.get());
            last = d;
        }
        if (args.length > 4) Thread.sleep(Long.MAX_VALUE);
    }
}
