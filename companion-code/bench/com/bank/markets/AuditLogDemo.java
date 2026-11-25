package com.bank.markets;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class AuditLogDemo {
    public static void main(String[] args) throws Exception {
        boolean sync = args[0].equals("sync");
        Path file = Path.of(args[1], "audit-" + args[0] + ".log");
        int seconds = Integer.parseInt(args[2]);
        LongAdder orders = new LongAdder();
        FileOutputStream out = new FileOutputStream(file.toFile());
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(8192);
        Thread writer = null;
        if (!sync) {
            writer = Thread.ofPlatform().name("audit-writer").daemon().start(() -> {
                java.util.List<String> batch = new java.util.ArrayList<>();
                try {
                    while (true) {
                        batch.add(queue.take());
                        queue.drainTo(batch, 511);
                        StringBuilder sb = new StringBuilder();
                        for (String l : batch) sb.append(l);
                        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                        batch.clear();
                    }
                } catch (Exception e) {  }
            });
        }
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        try (ExecutorService pool = Executors.newFixedThreadPool(50, Thread.ofPlatform().name("order-handler-", 1).factory())) {
            for (int t = 0; t < 50; t++) {
                pool.submit(() -> {
                    long id = 0;
                    while (System.nanoTime() < end) {
                        String line = "2026-11-08T09:00:00Z ORDER " + Thread.currentThread().getName() + "-" + (id++)
                                + " BUY 100 FR0000120271 LIMIT 112.40 ACCEPTED\n";
                        if (sync) {
                            synchronized (out) {
                                out.write(line.getBytes(StandardCharsets.UTF_8));
                                out.getFD().sync();
                            }
                        } else {
                            queue.put(line);
                        }
                        orders.increment();
                    }
                    return null;
                });
            }
            if (args.length > 3) { Thread.sleep(seconds * 500L); System.out.println("dump now"); Thread.sleep(seconds * 400L); }
        }
        System.out.printf("%s: %,d orders/s with 50 threads%n", args[0], orders.sum() / seconds);
        System.exit(0);
    }
}
