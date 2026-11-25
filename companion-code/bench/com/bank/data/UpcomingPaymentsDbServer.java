package com.bank.data;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class UpcomingPaymentsDbServer {
    public static void main(String[] args) throws Exception {
        int dbPort = Integer.parseInt(args[0]);
        int workers = Integer.parseInt(args[1]);
        int poolSize = Integer.parseInt(args[2]);
        String cast = args[3].equals("numeric") ? "::numeric" : "";
        int port = Integer.parseInt(args[4]);
        int seconds = Integer.parseInt(args[5]);
        SimplePool pool = new SimplePool(poolSize, dbPort, 30_000);
        LongAdder served = new LongAdder(), waitNs = new LongAdder(), dbNs = new LongAdder(), timeouts = new LongAdder();
        AtomicInteger busy = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 100);
        server.setExecutor(Executors.newFixedThreadPool(workers));
        server.createContext("/accounts/upcoming-payments", ex -> {
            busy.incrementAndGet();
            try {
                long customer = 1 + ThreadLocalRandom.current().nextInt(200_000);
                StringBuilder json = new StringBuilder("{\"customer\":" + customer + ",\"debits\":[");
                long t0 = System.nanoTime();
                PgWire c;
                try { c = pool.borrow(); } catch (Exception e) {
                    timeouts.increment();
                    ex.sendResponseHeaders(503, -1); ex.close(); return;
                }
                long t1 = System.nanoTime();
                try {
                    for (String[] acc : c.query("SELECT id, iban FROM account WHERE customer_id = " + customer)) {
                        for (String[] d : c.query("SELECT due_date, amount_cents, beneficiary FROM scheduled_payment "
                                + "WHERE account_id = " + acc[0] + cast + " AND status = 'PLANNED' "
                                + "AND due_date BETWEEN date '2026-11-25' AND date '2026-12-24' ORDER BY due_date")) {
                            json.append("{\"iban\":\"").append(acc[1]).append("\",\"date\":\"").append(d[0])
                                .append("\",\"amount\":").append(d[1]).append("},");
                        }
                    }
                } finally { pool.release(c); }
                long t2 = System.nanoTime();
                waitNs.add(t1 - t0); dbNs.add(t2 - t1);
                byte[] body = json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
                served.increment();
            } catch (Exception e) {
            } finally { busy.decrementAndGet(); }
        });
        server.start();
        for (int s = 5; s <= seconds; s += 5) {
            Thread.sleep(5000);
            long n = served.sumThenReset();
            double w = waitNs.sumThenReset() / 1e6, d = dbNs.sumThenReset() / 1e6;
            System.out.printf("t=%2ds answered/s=%4d busy workers=%3d avg wait for connection=%s avg db time=%s pool timeouts=%d%n",
                    s, n / 5, busy.get(), n == 0 ? "     -" : String.format("%6.0f ms", w / n),
                    n == 0 ? "     -" : String.format("%6.1f ms", d / n), timeouts.sumThenReset());
        }
        System.exit(0);
    }
}
