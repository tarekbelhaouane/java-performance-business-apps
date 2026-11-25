package com.bank.data;

import java.util.*;

public class StatementPageDemo {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int pages = Integer.parseInt(args[1]);
        try (PgWire db = new PgWire("localhost", port, "postgres", "norvane")) {
            for (int i = 0; i < 300; i++) db.query("SELECT 1");
            long t0 = System.nanoTime();
            for (int i = 0; i < 2000; i++) db.query("SELECT 1");
            System.out.printf("round trip (SELECT 1)          : %6.3f ms%n", (System.nanoTime() - t0) / 2000 / 1e6);
            t0 = System.nanoTime();
            for (int i = 0; i < 2000; i++) db.query("SELECT iban, balance_cents FROM account WHERE id = " + (1 + i * 97));
            System.out.printf("primary-key lookup             : %6.3f ms%n", (System.nanoTime() - t0) / 2000 / 1e6);
            for (String mode : new String[] {"n+1", "batch16", "join", "n+1", "batch16", "join"}) {
                long q0 = db.roundTrips, b0 = db.bytesReceived;
                t0 = System.nanoTime();
                for (int p = 0; p < pages; p++) page(db, 1 + (p * 7919) % 300000, mode);
                double ms = (System.nanoTime() - t0) / 1e6 / pages;
                System.out.printf("%-8s: %6.2f ms per page, %5.1f queries per page, %6.0f bytes per page%n",
                        mode, ms, (double) (db.roundTrips - q0) / pages, (double) (db.bytesReceived - b0) / pages);
            }
        }
    }

    static List<String> page(PgWire db, long account, String mode) throws Exception {
        List<String> lines = new ArrayList<>();
        if (mode.equals("join")) {
            for (String[] r : db.query("SELECT t.booked_at, t.amount_cents, m.name, m.category "
                    + "FROM card_transaction t JOIN merchant m ON m.id = t.merchant_id "
                    + "WHERE t.account_id = " + account + " ORDER BY t.booked_at DESC LIMIT 20"))
                lines.add(r[0] + " " + r[1] + " " + r[2] + " " + r[3]);
            return lines;
        }
        List<String[]> tx = db.query("SELECT booked_at, amount_cents, merchant_id FROM card_transaction "
                + "WHERE account_id = " + account + " ORDER BY booked_at DESC LIMIT 20");
        Map<String, String[]> merchants = new HashMap<>();
        if (mode.equals("n+1")) {
            for (String[] t : tx) {
                String[] m = db.query("SELECT name, category FROM merchant WHERE id = " + t[2]).get(0);
                lines.add(t[0] + " " + t[1] + " " + m[0] + " " + m[1]);
            }
            return lines;
        }
        List<String> ids = tx.stream().map(t -> t[2]).distinct().toList();
        for (int i = 0; i < ids.size(); i += 16) {
            String in = String.join(",", ids.subList(i, Math.min(ids.size(), i + 16)));
            for (String[] m : db.query("SELECT id, name, category FROM merchant WHERE id IN (" + in + ")"))
                merchants.put(m[0], m);
        }
        for (String[] t : tx) {
            String[] m = merchants.get(t[2]);
            lines.add(t[0] + " " + t[1] + " " + m[1] + " " + m[2]);
        }
        return lines;
    }
}
