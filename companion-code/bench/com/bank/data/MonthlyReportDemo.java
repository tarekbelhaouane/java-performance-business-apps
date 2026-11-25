package com.bank.data;

import java.lang.management.ManagementFactory;
import java.util.*;

public class MonthlyReportDemo {
    static final String MONTH = "t.booked_at >= timestamp '2026-03-01' AND t.booked_at < timestamp '2026-04-01'";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String mode = args[1];
        Map<String, Long> totals = new TreeMap<>();
        long rows = 0, peakHeap = 0;
        long t0 = System.nanoTime();
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            String select = "SELECT m.category, t.amount_cents FROM card_transaction t "
                    + "JOIN merchant m ON m.id = t.merchant_id WHERE " + MONTH;
            switch (mode) {
                case "load" -> {
                    List<String[]> all = db.query(select);
                    rows = all.size();
                    peakHeap = liveHeapMb();
                    for (String[] r : all) totals.merge(r[0], Long.parseLong(r[1]), Long::sum);
                }
                case "cursor" -> {
                    db.query("BEGIN");
                    db.query("DECLARE month_cursor NO SCROLL CURSOR FOR " + select);
                    List<String[]> batch;
                    do {
                        batch = db.query("FETCH 1000 FROM month_cursor");
                        rows += batch.size();
                        for (String[] r : batch) totals.merge(r[0], Long.parseLong(r[1]), Long::sum);
                        if (rows == 100_000) peakHeap = liveHeapMb();
                    } while (!batch.isEmpty());
                    db.query("COMMIT");
                }
                default -> {
                    for (String[] r : db.query("SELECT m.category, sum(t.amount_cents) FROM card_transaction t "
                            + "JOIN merchant m ON m.id = t.merchant_id WHERE " + MONTH + " GROUP BY m.category")) {
                        totals.put(r[0], Long.parseLong(r[1]));
                    }
                    rows = totals.size();
                    peakHeap = liveHeapMb();
                }
            }
            System.out.printf("%-6s rows received=%,d  time=%,d ms  bytes received=%,d KB  round trips=%d  live heap=%d MB%n",
                    mode, rows, (System.nanoTime() - t0) / 1_000_000, db.bytesReceived / 1024, db.roundTrips, peakHeap);
        }
        System.out.println("totals: " + totals);
    }

    static long liveHeapMb() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }
}
