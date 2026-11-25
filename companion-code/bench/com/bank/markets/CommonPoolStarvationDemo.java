package com.bank.markets;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

public class CommonPoolStarvationDemo {
    static void blockingCall(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static void main(String[] args) throws Exception {
        boolean dedicated = args[0].equals("dedicated");
        System.out.println("availableProcessors=" + Runtime.getRuntime().availableProcessors()
                + " commonPool parallelism=" + ForkJoinPool.commonPool().getParallelism());
        ExecutorService riskPool = Executors.newFixedThreadPool(16, Thread.ofPlatform().name("eod-risk-", 1).factory());
        Runnable riskJob = () -> IntStream.range(0, 64).parallel().forEach(i -> blockingCall(200));
        Future<?> risk = dedicated
                ? riskPool.submit(() -> {
                    List<Future<?>> fs = new ArrayList<>();
                    for (int i = 0; i < 64; i++) fs.add(riskPool.submit(() -> blockingCall(200)));
                    for (Future<?> f : fs) { try { f.get(); } catch (Exception e) { throw new RuntimeException(e); } }
                })
                : CompletableFuture.runAsync(riskJob);
        Thread.sleep(100);
        long[] lat = new long[50];
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            CompletableFuture.supplyAsync(() -> { blockingCall(2); return 1; }).get();
            lat[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        Arrays.sort(lat);
        System.out.printf("%s: API task p50=%d ms max=%d ms%n", args[0], lat[25], lat[49]);
        risk.get();
        riskPool.shutdown();
    }
}
