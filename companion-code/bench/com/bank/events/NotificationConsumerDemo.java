package com.bank.events;

import com.bank.data.PgWire;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class NotificationConsumerDemo {
    record PaymentEvent(long offset, long accountId, long amountCents) { }

    public static void main(String[] args) throws Exception {
        int dbPort = Integer.parseInt(args[0]);
        URI push = URI.create("http://localhost:" + args[1] + "/score");
        int polls = Integer.parseInt(args[2]);
        boolean batched = args[3].equals("batched");
        int perPoll = args.length > 4 ? Integer.parseInt(args[4]) : 500;
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        try (PgWire db = new PgWire("localhost", dbPort, "postgres", "norvane")) {
            db.query("CREATE TABLE IF NOT EXISTS notification_log (event_offset bigint, account_id bigint, amount_cents bigint)");
            db.query("TRUNCATE notification_log");
            long offset = 0, t0 = System.nanoTime(), maxPollMs = 0;
            for (int p = 0; p < polls; p++) {
                List<PaymentEvent> records = new ArrayList<>();
                for (int i = 0; i < perPoll; i++, offset++) records.add(new PaymentEvent(offset, 1 + (offset * 7) % 3000, 1000 + offset % 900));
                long p0 = System.nanoTime();
                if (!batched) {
                    for (PaymentEvent e : records) {
                        http.send(HttpRequest.newBuilder(push).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding());
                        db.query("INSERT INTO notification_log VALUES (" + e.offset() + "," + e.accountId() + "," + e.amountCents() + ")");
                    }
                } else {
                    Map<Long, List<PaymentEvent>> byAccount = new LinkedHashMap<>();
                    for (PaymentEvent e : records) byAccount.computeIfAbsent(e.accountId(), k -> new ArrayList<>()).add(e);
                    Semaphore inFlight = new Semaphore(50);
                    try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (List<PaymentEvent> perAccount : byAccount.values()) {
                            ex.submit(() -> {
                                for (PaymentEvent e : perAccount) {
                                    inFlight.acquire();
                                    try { http.send(HttpRequest.newBuilder(push).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.discarding()); }
                                    finally { inFlight.release(); }
                                }
                                return null;
                            });
                        }
                    }
                    StringJoiner values = new StringJoiner(",");
                    for (PaymentEvent e : records) values.add("(" + e.offset() + "," + e.accountId() + "," + e.amountCents() + ")");
                    db.query("INSERT INTO notification_log VALUES " + values);
                }
                maxPollMs = Math.max(maxPollMs, (System.nanoTime() - p0) / 1_000_000);
            }
            double s = (System.nanoTime() - t0) / 1e9;
            System.out.printf("%-10s %,d events in %.1f s = %,.0f events/s per consumer thread, slowest poll %,d ms%n",
                    args[3], offset, s, offset / s, maxPollMs);
        }
    }
}
