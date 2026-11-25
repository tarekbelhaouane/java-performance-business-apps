package com.bank.accounts.web;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class UpcomingPaymentsServer {
    static final AtomicInteger busy = new AtomicInteger();
    static final AtomicInteger maxBusy = new AtomicInteger();
    static final LongAdder poolWaitNanos = new LongAdder();
    static final LongAdder served = new LongAdder();
    static final AtomicLong maxPoolWait = new AtomicLong();

    public static void main(String[] args) throws Exception {
        boolean virtual = args[0].equals("virtual");
        int workers = Integer.parseInt(args[1]);
        long queryMs = Long.parseLong(args[2]);
        int poolSize = Integer.parseInt(args[3]);
        int port = Integer.parseInt(args[4]);
        int seconds = Integer.parseInt(args[5]);
        Semaphore pool = poolSize > 0 ? new Semaphore(poolSize, true) : null;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 100);
        server.setExecutor(virtual ? Executors.newVirtualThreadPerTaskExecutor()
                                   : Executors.newFixedThreadPool(workers));
        server.createContext("/accounts/upcoming-payments", exchange -> {
            int b = busy.incrementAndGet();
            maxBusy.accumulateAndGet(b, Math::max);
            try {
                long t0 = System.nanoTime();
                if (pool != null) pool.acquireUninterruptibly();
                long waited = System.nanoTime() - t0;
                poolWaitNanos.add(waited);
                maxPoolWait.accumulateAndGet(waited, Math::max);
                try {
                    Thread.sleep(queryMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (pool != null) pool.release();
                }
                byte[] body = ("{\"account\":\"FR76...0189\",\"upcoming\":[{\"label\":\"Rent\","
                        + "\"amount\":\"850.00\",\"date\":\"2026-11-30\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                served.increment();
            } finally {
                busy.decrementAndGet();
            }
        });
        server.start();
        for (int s = 5; s <= seconds; s += 5) {
            Thread.sleep(5_000);
            long n = served.sumThenReset();
            System.out.printf("t=%2ds served/s=%4d busy now=%3d max busy=%3d avg pool wait=%5.0f ms max pool wait=%5d ms%n",
                    s, n / 5, busy.get(), maxBusy.getAndSet(0),
                    n == 0 ? 0 : poolWaitNanos.sumThenReset() / 1e6 / n, maxPoolWait.getAndSet(0) / 1_000_000);
        }
        server.stop(0);
        System.exit(0);
    }
}
