package com.bank.markets;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfirmationFanOutDemo {
    public static void main(String[] args) throws Exception {
        boolean virtual = args[0].equals("virtual");
        AtomicInteger inFlight = new AtomicInteger(), maxInFlight = new AtomicInteger();
        long t0 = System.nanoTime();
        try (ExecutorService ex = virtual ? Executors.newVirtualThreadPerTaskExecutor()
                                          : Executors.newFixedThreadPool(200)) {
            for (int i = 0; i < 10_000; i++) {
                ex.submit(() -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try { Thread.sleep(100); } finally { inFlight.decrementAndGet(); }
                    return null;
                });
            }
            if (args.length > 1) { Thread.sleep(500); System.out.println("RSS during run: " + rssMb() + " MB"); }
        }
        System.out.printf("%s: 10,000 confirmations in %,d ms, max concurrent calls %,d, platform threads now %d%n",
                args[0], (System.nanoTime() - t0) / 1_000_000, maxInFlight.get(), Thread.activeCount());
    }

    static long rssMb() throws Exception {
        for (String l : Files.readAllLines(Path.of("/proc/self/status")))
            if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) >> 10;
        return -1;
    }
}
