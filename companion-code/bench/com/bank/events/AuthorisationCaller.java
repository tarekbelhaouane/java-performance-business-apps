package com.bank.events;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class AuthorisationCaller {
    public static void main(String[] args) throws Exception {
        URI uri = URI.create("http://localhost:" + args[0] + "/score");
        int rate = Integer.parseInt(args[1]), seconds = Integer.parseInt(args[2]);
        Duration timeout = Duration.ofMillis(Long.parseLong(args[3]));
        String policy = args[4];
        HttpClient client = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        LongAdder calls = new LongAdder(), retries = new LongAdder(), scored = new LongAdder(),
                  fallback = new LongAdder(), shortCircuited = new LongAdder();
        AtomicLong windowCalls = new AtomicLong(), windowFailures = new AtomicLong();
        AtomicLong openUntil = new AtomicLong();
        long start = System.nanoTime();
        ScheduledExecutorService report = Executors.newSingleThreadScheduledExecutor();
        report.scheduleAtFixedRate(() -> {
            System.out.printf("t=%2ds calls/s=%4d (retries %4d)  scored=%4d  fallback=%4d  short-circuited=%4d%n",
                    (System.nanoTime() - start) / 1_000_000_000L, calls.sumThenReset() / 2, retries.sumThenReset() / 2,
                    scored.sumThenReset() / 2, fallback.sumThenReset() / 2, shortCircuited.sumThenReset() / 2);
            windowCalls.set(0); windowFailures.set(0);
        }, 2, 2, TimeUnit.SECONDS);
        try (ExecutorService payments = Executors.newVirtualThreadPerTaskExecutor()) {
            for (long i = 0; i < (long) rate * seconds; i++) {
                long intended = start + (long) (i * (1e9 / rate));
                long wait = intended - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                payments.submit(() -> {
                    long deadline = System.nanoTime() + 1_000_000_000L;
                    int attempts = policy.equals("retry3") || policy.equals("budget") ? 4 : 1;
                    for (int a = 0; a < attempts && System.nanoTime() < deadline; a++) {
                        if (policy.equals("breaker") && System.nanoTime() < openUntil.get()) {
                            shortCircuited.increment(); break;
                        }
                        if (a > 0) {
                            if (policy.equals("budget") && retries.sum() * 10 > Math.max(10, calls.sum())) break;
                            retries.increment();
                        }
                        calls.increment(); windowCalls.incrementAndGet();
                        try {
                            HttpResponse<Void> r = client.send(HttpRequest.newBuilder(uri).timeout(timeout).build(),
                                    HttpResponse.BodyHandlers.discarding());
                            if (r.statusCode() == 200) { scored.increment(); return; }
                        } catch (Exception e) {
                            long f = windowFailures.incrementAndGet();
                            if (policy.equals("breaker") && windowCalls.get() > 20 && f * 2 > windowCalls.get())
                                openUntil.set(System.nanoTime() + 2_000_000_000L);
                        }
                    }
                    fallback.increment();
                });
            }
        }
        Thread.sleep(2100);
        System.exit(0);
    }
}
