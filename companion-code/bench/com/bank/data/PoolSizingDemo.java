package com.bank.data;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class PoolSizingDemo {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        for (int a = 2; a < args.length; a++) {
            int size = Integer.parseInt(args[a]);
            try (SimplePool pool = new SimplePool(size, port, 30_000)) {
                LongAdder done = new LongAdder(), waitNs = new LongAdder(), queryNs = new LongAdder();
                long end = System.nanoTime() + seconds * 1_000_000_000L;
                try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int t = 0; t < 100; t++) {
                        final int seed = t;
                        ex.submit(() -> {
                            Random rnd = new Random(seed);
                            while (System.nanoTime() < end) {
                                long t0 = System.nanoTime();
                                PgWire c = pool.borrow();
                                long t1 = System.nanoTime();
                                try {
                                    c.query("SELECT t.booked_at, t.amount_cents, m.name FROM card_transaction t "
                                            + "JOIN merchant m ON m.id = t.merchant_id WHERE t.account_id = "
                                            + (1 + rnd.nextInt(300000)) + " ORDER BY t.booked_at DESC LIMIT 20");
                                } finally { pool.release(c); }
                                long t2 = System.nanoTime();
                                waitNs.add(t1 - t0); queryNs.add(t2 - t1); done.increment();
                            }
                            return null;
                        });
                    }
                }
                long n = done.sum();
                System.out.printf("pool %3d: %,7d pages/s   wait for connection %6.2f ms   query %5.2f ms%n",
                        size, n / seconds, waitNs.sum() / 1e6 / n, queryNs.sum() / 1e6 / n);
            }
        }
    }
}
