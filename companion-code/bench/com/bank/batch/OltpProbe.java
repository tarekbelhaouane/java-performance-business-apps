package com.bank.batch;

import com.bank.data.PgWire;
import java.util.*;

public final class OltpProbe {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long end = System.nanoTime() + Long.parseLong(args[1]) * 1_000_000_000L;
        long interval = Long.parseLong(args[2]);
        List<Double> ms = new ArrayList<>();
        Random r = new Random(7);
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            long next = System.nanoTime();
            java.nio.file.Path stop = java.nio.file.Path.of("/tmp/stop-probe");
            while (System.nanoTime() < end && !java.nio.file.Files.exists(stop)) {
                long t0 = System.nanoTime();
                db.query("SELECT booked_at, amount_cents FROM card_transaction "
                        + "WHERE account_id = " + (1 + r.nextInt(120_000))
                        + " ORDER BY booked_at DESC LIMIT 20");
                ms.add((System.nanoTime() - t0) / 1e6);
                next += interval * 1_000_000L;
                long sleep = (next - System.nanoTime()) / 1_000_000L;
                if (sleep > 0) Thread.sleep(sleep);
            }
        }
        Collections.sort(ms);
        System.out.printf("%s: %d queries, p50 %.2f ms, p99 %.2f ms, max %.2f ms%n",
                args[3], ms.size(), ms.get(ms.size() / 2),
                ms.get((int) (ms.size() * 0.99)), ms.get(ms.size() - 1));
    }
}
