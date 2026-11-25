package com.bank.data;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class WriteDemo {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            db.query("DROP TABLE IF EXISTS fee_posting; CREATE TABLE fee_posting (id bigint PRIMARY KEY, "
                    + "account_id bigint, amount_cents bigint, label text)");
            db.query("DROP TABLE IF EXISTS fee_account; CREATE TABLE fee_account (id int PRIMARY KEY, balance_cents bigint)");
            db.query("INSERT INTO fee_account SELECT g, 0 FROM generate_series(0, 31) g");
        }
        if (args[1].equals("batch")) batch(port); else hotRow(port, Integer.parseInt(args[2]));
    }

    static void batch(int port) throws Exception {
        int n = 20_000;
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            for (String mode : new String[] {"row by row, autocommit", "row by row, one transaction", "500 rows per statement"}) {
                db.query("TRUNCATE fee_posting");
                long q0 = db.roundTrips, t0 = System.nanoTime();
                if (mode.startsWith("row by row, one")) db.query("BEGIN");
                if (mode.startsWith("row")) {
                    for (int i = 0; i < n; i++)
                        db.query("INSERT INTO fee_posting VALUES (" + i + "," + (1 + i % 300000) + ",50,'Monthly card fee')");
                } else {
                    db.query("BEGIN");
                    for (int i = 0; i < n; i += 500) {
                        StringBuilder sb = new StringBuilder("INSERT INTO fee_posting VALUES ");
                        for (int k = i; k < i + 500; k++)
                            sb.append(k == i ? "" : ",").append("(").append(k).append(",").append(1 + k % 300000)
                              .append(",50,'Monthly card fee')");
                        db.query(sb.toString());
                    }
                }
                if (!mode.startsWith("row by row, autocommit")) db.query("COMMIT");
                double s = (System.nanoTime() - t0) / 1e9;
                System.out.printf("%-30s %6.2f s  %,8.0f rows/s  %,6d round trips%n", mode, s, n / s, db.roundTrips - q0);
            }
        }
    }

    static void hotRow(int port, int seconds) throws Exception {
        for (int accounts : new int[] {1, 32}) {
            LongAdder done = new LongAdder();
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            try (ExecutorService ex = Executors.newFixedThreadPool(8)) {
                for (int t = 0; t < 8; t++) {
                    final int thread = t;
                    ex.submit(() -> {
                        try (PgWire c = new PgWire("localhost", port, "postgres", "norvane")) {
                            long id = thread * 10_000_000L + accounts * 1_000_000_000L;
                            Random rnd = new Random(thread);
                            while (System.nanoTime() < end) {
                                id++;
                                int fee = accounts == 1 ? 0 : rnd.nextInt(accounts);
                                c.query("BEGIN");
                                c.query("UPDATE fee_account SET balance_cents = balance_cents + 50 WHERE id = " + fee);
                                c.query("INSERT INTO fee_posting VALUES (" + id + "," + (1 + id % 300000)
                                        + ",50,'Instant transfer fee')");
                                c.query("COMMIT");
                                done.increment();
                            }
                        }
                        return null;
                    });
                }
            }
            System.out.printf("fee accounts=%2d : %,6d transfers/s with 8 threads%n", accounts, done.sum() / seconds);
        }
    }
}
