package com.bank.method;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class PeriodicCausesDemo {
    static final Object RATE_CARD = new Object();
    static volatile long rate = 42;

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        long seconds = Long.parseLong(args[2]);
        Map<Long, long[]> liveSet = new HashMap<>();
        if (mode.equals("gc")) {
            for (long i = 0; i < 6_000_000; i++) liveSet.put(i, new long[10]);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1000);
        server.setExecutor(Executors.newFixedThreadPool(200));
        server.createContext("/accounts/upcoming-payments", ex -> {
            long r;
            if (mode.equals("lock")) {
                synchronized (RATE_CARD) { r = rate; }
            } else {
                r = rate;
            }
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
            ex.sendResponseHeaders(r > 0 ? 200 : 500, -1);
            ex.close();
        });
        server.start();
        System.out.printf("mode=%s live entries=%,d%n", mode, liveSet.size());
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            Thread.sleep(10_000);
            long t0 = System.nanoTime();
            if (mode.equals("lock")) {
                synchronized (RATE_CARD) {
                    Thread.sleep(450);
                    rate++;
                }
            } else {
                System.gc();
            }
            System.out.printf("event at %.1f s took %d ms%n", (System.nanoTime() - end) / 1e9 + seconds,
                    (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println(liveSet.size());
        System.exit(0);
    }
}
