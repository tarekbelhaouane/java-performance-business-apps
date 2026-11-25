package com.bank.payments.quote;

public class DeoptDemo {
    public static void main(String[] args) {
        int n = 20_000;
        TransferRequest[] reqs = Requests.make(n, 1, 7);
        QuoteBatch batch = new QuoteBatch();
        long[] t = new long[400];
        long sink = 0;
        for (int r = 0; r < 400; r++) {
            if (r == 199) {
                System.out.println("--- premium offer enabled ---");
                for (int i = 0; i < n; i += 10) {
                    TransferRequest o = reqs[i];
                    reqs[i] = new TransferRequest(o.fromIban(), o.toIban(), o.amount(),
                            o.instant(), Segment.PREMIUM);
                }
            }
            long t0 = System.nanoTime();
            sink += batch.quoteAll(reqs);
            t[r] = System.nanoTime() - t0;
        }
        if (args.length > 0)
            for (int r = 0; r < 400; r++) System.out.printf("%d,%.1f%n", r + 1, t[r] / (double) n);
        System.err.println(sink);
    }
}
