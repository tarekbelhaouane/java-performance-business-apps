package com.bank.payments.quote;

public class WarmupCurve {
    public static void main(String[] args) {
        int n = 20_000, rounds = Integer.parseInt(args[0]);
        TransferRequest[] reqs = Requests.make(n, 1, 7);
        QuoteBatch batch = new QuoteBatch();
        long[] t = new long[rounds];
        long sink = 0;
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            sink += batch.quoteAll(reqs);
            t[r] = System.nanoTime() - t0;
        }
        for (int r = 0; r < rounds; r++) System.out.printf("%d,%.1f%n", r + 1, t[r] / (double) n);
        System.err.println(sink);
    }
}
