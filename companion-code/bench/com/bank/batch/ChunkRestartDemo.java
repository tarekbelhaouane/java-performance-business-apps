package com.bank.batch;

import com.bank.data.PgWire;

import java.io.IOException;
import java.nio.file.*;

public final class ChunkRestartDemo {
    static final int ACCOUNTS = 10_000, CHUNK = 100, CRASH_CHUNK = 37;
    static final Path NOTIFICATIONS = Path.of("/tmp/notifications.log");

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        boolean sameTx = args[1].equals("same");
        boolean idempotent = args[2].equals("idempotent");
        boolean crash = args.length > 3 && args[3].equals("crash");
        try (PgWire biz = new PgWire("localhost", port, "postgres", "norvane");
             PgWire repo = new PgWire("localhost", port, "postgres", "norvane")) {
            if (crash) setup(biz, idempotent);
            long start = Long.parseLong(repo.query(
                    "SELECT last_key FROM batch_checkpoint WHERE job = 'statements'")
                    .get(0)[0]);
            System.out.printf("%s start after account %d%n",
                    crash ? "first run:" : "restart:  ", start);
            for (long from = start + 1; from <= ACCOUNTS; from += CHUNK) {
                long to = from + CHUNK - 1;
                int chunk = (int) (from / CHUNK) + 1;
                notify(from, to);
                String insert = insertSql(from, to, idempotent);
                String checkpoint = "UPDATE batch_checkpoint SET last_key = " + to
                        + " WHERE job = 'statements';";
                if (sameTx) {
                    if (crash && chunk == CRASH_CHUNK) {
                        biz.query("BEGIN;" + insert + checkpoint);
                        Runtime.getRuntime().halt(137);
                    }
                    biz.query("BEGIN;" + insert + checkpoint + "COMMIT;");
                } else {
                    biz.query("BEGIN;" + insert + "COMMIT;");
                    if (crash && chunk == CRASH_CHUNK) {
                        Runtime.getRuntime().halt(137);
                    }
                    repo.query(checkpoint);
                }
            }
            String[] r = biz.query("SELECT count(*), count(DISTINCT account_id) "
                    + "FROM statement").get(0);
            long lines = Files.readAllLines(NOTIFICATIONS).size();
            System.out.printf("result:    %s rows, %s distinct accounts, %s "
                    + "duplicates, %d notifications for %d accounts%n",
                    r[0], r[1], Long.parseLong(r[0]) - Long.parseLong(r[1]),
                    lines, ACCOUNTS);
        }
    }

    static void setup(PgWire db, boolean idempotent) throws IOException {
        db.query("DROP TABLE IF EXISTS statement; DROP TABLE IF EXISTS "
                + "batch_checkpoint;"
                + "CREATE TABLE statement (account_id bigint NOT NULL, "
                + "month text NOT NULL, closing_balance numeric(15,2));"
                + (idempotent ? "ALTER TABLE statement ADD UNIQUE "
                + "(account_id, month);" : "")
                + "CREATE TABLE batch_checkpoint (job text PRIMARY KEY, "
                + "last_key bigint NOT NULL);"
                + "INSERT INTO batch_checkpoint VALUES ('statements', 0);");
        Files.deleteIfExists(NOTIFICATIONS);
        Files.createFile(NOTIFICATIONS);
    }

    static String insertSql(long from, long to, boolean idempotent) {
        return "INSERT INTO statement SELECT a, '2026-10', a * 1.5 "
                + "FROM generate_series(" + from + ", " + to + ") a"
                + (idempotent ? " ON CONFLICT DO NOTHING;" : ";");
    }

    static void notify(long from, long to) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (long a = from; a <= to; a++) sb.append(a).append('\n');
        Files.writeString(NOTIFICATIONS, sb, StandardOpenOption.APPEND);
    }
}
