package com.bank.batch;

import com.bank.data.PgWire;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class StatementBatch {
    record Line(String bookedAt, long amountCents) { }
    record Statement(long accountId, String iban, long balanceCents, long cardTotal, long interest, List<Line> lines) { }

    static final class JobSummary {
        final List<Statement> processed = Collections.synchronizedList(new ArrayList<>());
        final LongAdder count = new LongAdder(), total = new LongAdder();
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int accounts = Integer.parseInt(args[1]);
        int chunk = Integer.parseInt(args[2]);
        int threads = Integer.parseInt(args[3]);
        boolean perItem = args[4].equals("item");
        boolean keep = args[5].equals("keep");
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            db.query("CREATE TABLE IF NOT EXISTS monthly_statement (account_id bigint, month text, balance_cents bigint, "
                    + "card_total_cents bigint, interest_cents bigint, lines int)");
            db.query("TRUNCATE monthly_statement");
        }
        JobSummary summary = new JobSummary();
        long t0 = System.nanoTime();
        int per = accounts / threads;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> parts = new ArrayList<>();
            for (int p = 0; p < threads; p++) {
                long from = 1 + (long) p * per, to = from + per - 1;
                parts.add(pool.submit(() -> { partition(port, from, to, chunk, perItem, keep, summary, t0); return null; }));
            }
            for (Future<?> f : parts) f.get();
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("accounts=%,d chunk=%d threads=%d lookup=%s : %.1f s, %,.0f accounts/s, live heap %d MB%n",
                summary.count.sum(), chunk, threads, args[4], s, summary.count.sum() / s, liveHeapMb());
    }

    static void partition(int port, long from, long to, int chunk, boolean perItem, boolean keep,
                          JobSummary summary, long t0) throws Exception {
        try (PgWire reader = new PgWire("localhost", port, "postgres", "norvane");
             PgWire writer = new PgWire("localhost", port, "postgres", "norvane")) {
            reader.query("BEGIN READ ONLY");
            reader.query("DECLARE accounts NO SCROLL CURSOR FOR SELECT id, iban, balance_cents FROM account "
                    + "WHERE id BETWEEN " + from + " AND " + to + " ORDER BY id");
            while (true) {
                List<String[]> items = reader.query("FETCH " + chunk + " FROM accounts");
                if (items.isEmpty()) break;
                Map<Long, List<Line>> tx = new HashMap<>();
                if (!perItem) {
                    StringJoiner ids = new StringJoiner(",");
                    for (String[] a : items) ids.add(a[0]);
                    for (String[] r : writer.query("SELECT account_id, booked_at, amount_cents FROM card_transaction "
                            + "WHERE account_id IN (" + ids + ") AND booked_at >= timestamp '2026-03-01' "
                            + "AND booked_at < timestamp '2026-04-01'"))
                        tx.computeIfAbsent(Long.parseLong(r[0]), k -> new ArrayList<>()).add(new Line(r[1], Long.parseLong(r[2])));
                }
                StringBuilder insert = new StringBuilder("INSERT INTO monthly_statement VALUES ");
                for (int i = 0; i < items.size(); i++) {
                    String[] a = items.get(i);
                    long id = Long.parseLong(a[0]);
                    List<Line> lines = new ArrayList<>();
                    if (perItem) {
                        for (String[] r : writer.query("SELECT booked_at, amount_cents FROM card_transaction WHERE account_id = "
                                + id + " AND booked_at >= timestamp '2026-03-01' AND booked_at < timestamp '2026-04-01'"))
                            lines.add(new Line(r[0], Long.parseLong(r[1])));
                    } else {
                        lines = tx.getOrDefault(id, List.of());
                    }
                    long balance = Long.parseLong(a[2]);
                    long card = lines.stream().mapToLong(Line::amountCents).sum();
                    long interest = balance * 25 / 10_000 / 12;
                    Statement st = new Statement(id, a[1], balance, card, interest, lines);
                    if (keep) summary.processed.add(st);
                    summary.count.increment();
                    summary.total.add(card);
                    insert.append(i == 0 ? "" : ",").append("(").append(id).append(",'2026-03',").append(balance)
                          .append(",").append(card).append(",").append(interest).append(",").append(lines.size()).append(")");
                }
                writer.query("BEGIN; " + insert + "; COMMIT");
                long n = summary.count.sum();
                if (keep && n % 50_000 < chunk && from == 1)
                    System.out.printf("  %,7d accounts, %5.1f s, live heap %d MB%n", n, (System.nanoTime() - t0) / 1e9, liveHeapMb());
            }
            reader.query("COMMIT");
        }
    }

    static long liveHeapMb() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }
}
