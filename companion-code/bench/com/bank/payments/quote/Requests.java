package com.bank.payments.quote;

import java.util.Random;

final class Requests {
    static TransferRequest[] make(int n, int segments, long seed) {
        Random rnd = new Random(seed);
        Segment[] all = Segment.values();
        TransferRequest[] out = new TransferRequest[n];
        for (int i = 0; i < n; i++) {
            out[i] = new TransferRequest("FR7630006000011234567890189", "FR7610107001011234567890129",
                    Money.eur(1_000 + rnd.nextInt(5_000_000)), (i & 1) == 0, all[rnd.nextInt(segments)]);
        }
        return out;
    }
}
