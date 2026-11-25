package com.bank.payments.quote;

import java.lang.management.ManagementFactory;

public class QuoteBench {
    public static void main(String[] args) {
        int segments = Integer.parseInt(args[0]);
        int n = 20_000;
        TransferRequest[] reqs = Requests.make(n, segments, 42);
        QuoteBatch batch = new QuoteBatch();
        com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long sink = 0;
        int warm = args.length > 1 ? Integer.parseInt(args[1]) : 1500;
        for (int w = 0; w < warm; w++) sink += batch.quoteAll(reqs);
        long best = Long.MAX_VALUE, bytes = Long.MAX_VALUE;
        for (int r = 0; r < 30; r++) {
            long tid = Thread.currentThread().threadId();
            long b0 = mx.getThreadAllocatedBytes(tid);
            long t0 = System.nanoTime();
            sink += batch.quoteAll(reqs);
            long t = System.nanoTime() - t0;
            long b = mx.getThreadAllocatedBytes(tid) - b0;
            best = Math.min(best, t); bytes = Math.min(bytes, b);
        }
        System.out.printf("segments=%d ns/quote=%.1f bytes/quote=%.1f sink=%d%n",
                segments, best / (double) n, bytes / (double) n, sink);
    }
}
