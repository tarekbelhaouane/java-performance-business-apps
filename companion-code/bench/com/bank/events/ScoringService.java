package com.bank.events;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ScoringService {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long normal = Long.parseLong(args[1]), slow = Long.parseLong(args[2]);
        long slowAfter = Long.parseLong(args[3]) * 1_000_000_000L;
        int seconds = Integer.parseInt(args[4]);
        int maxQueue = Integer.parseInt(args[5]);
        Semaphore workers = new Semaphore(20);
        AtomicInteger waiting = new AtomicInteger();
        LongAdder shed = new LongAdder();
        long start = System.nanoTime();
        LongAdder received = new LongAdder(), completed = new LongAdder();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 1000);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/score", ex -> {
            received.increment();
            if (maxQueue > 0 && waiting.get() >= maxQueue) {
                shed.increment();
                ex.sendResponseHeaders(503, -1); ex.close(); return;
            }
            waiting.incrementAndGet();
            workers.acquireUninterruptibly();
            waiting.decrementAndGet();
            try {
                Thread.sleep(System.nanoTime() - start > slowAfter ? slow : normal);
                ex.getResponseHeaders().set("X-Fraud-Score", "12");
                ex.sendResponseHeaders(200, -1);
                ex.close();
                completed.increment();
            } catch (Exception e) {  } finally { workers.release(); }
        });
        server.start();
        for (int s = 2; s <= seconds; s += 2) {
            Thread.sleep(2000);
            System.out.printf("t=%2ds scoring received/s=%4d answers sent/s=%4d shed/s=%4d%n", s,
                    received.sumThenReset() / 2, completed.sumThenReset() / 2, shed.sumThenReset() / 2);
        }
        System.exit(0);
    }
}
