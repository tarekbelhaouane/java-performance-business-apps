package com.bank.payments.cards;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class SingleFlightDemo {
    static final class Partner {
        final AtomicInteger calls = new AtomicInteger();
        final Map<String, Long> latencyMs = new ConcurrentHashMap<>();
        final Set<String> failOnce = ConcurrentHashMap.newKeySet();
        final AtomicInteger version = new AtomicInteger(1);

        int fetch(String card) {
            calls.incrementAndGet();
            int dataVersion = version.get();
            sleep(latencyMs.getOrDefault(card, 5L));
            if (failOnce.remove(card)) {
                throw new IllegalStateException("partner 503 for " + card);
            }
            return card.length() * 10 + dataVersion;
        }
    }

    interface Scores { int score(String card) throws Exception; }

    static final class GlobalLock implements Scores {
        final Map<String, Integer> m = new HashMap<>(); final Partner p;
        GlobalLock(Partner p) { this.p = p; }
        public synchronized int score(String c) {
            return m.computeIfAbsent(c, p::fetch);
        }
    }

    static final class ShortLock implements Scores {
        final Map<String, Integer> m = new HashMap<>(); final Partner p;
        ShortLock(Partner p) { this.p = p; }
        public int score(String c) {
            synchronized (this) { Integer v = m.get(c); if (v != null) return v; }
            int fresh = p.fetch(c);
            synchronized (this) { m.putIfAbsent(c, fresh); }
            return fresh;
        }
    }

    static final class SingleFlight implements Scores {
        final ConcurrentHashMap<String, CompletableFuture<Integer>> m =
                new ConcurrentHashMap<>();
        final Function<String, Integer> fetch; final long waitMs;
        SingleFlight(Function<String, Integer> fetch, long waitMs) {
            this.fetch = fetch; this.waitMs = waitMs;
        }
        public int score(String card) throws Exception {
            CompletableFuture<Integer> mine = new CompletableFuture<>();
            CompletableFuture<Integer> f = m.putIfAbsent(card, mine);
            if (f == null) {
                f = mine;
                try {
                    mine.complete(fetch.apply(card));
                } catch (RuntimeException e) {
                    m.remove(card, mine);
                    mine.completeExceptionally(e);
                }
            }
            try {
                return f.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw (Exception) e.getCause();
            }
        }
        void invalidate(String card) {
            CompletableFuture<Integer> f = m.get(card);
            if (f != null) m.remove(card, f);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("JDK " + Runtime.version()
                + ", CPUs " + Runtime.getRuntime().availableProcessors());
        duplicates();
        independentKeys();
        ownerFailure();
        waiterTimeout();
        invalidation();
    }

    static void duplicates() throws Exception {
        for (String name : List.of("short lock", "single flight")) {
            Partner p = new Partner();
            p.latencyMs.put("card-1", 50L);
            Scores s = name.equals("short lock") ? new ShortLock(p)
                    : new SingleFlight(p::fetch, 1_000);
            long ms = burst(s, 200, i -> "card-1");
            System.out.printf("1 duplicates   %-13s 200 callers -> %3d partner "
                    + "calls, %d ms%n", name, p.calls.get(), ms);
        }
    }

    static void independentKeys() throws Exception {
        for (String name : List.of("global lock", "single flight")) {
            Partner p = new Partner();
            p.latencyMs.put("card-slow", 500L);
            p.latencyMs.put("card-fast", 5L);
            Scores s = name.equals("global lock") ? new GlobalLock(p)
                    : new SingleFlight(p::fetch, 2_000);
            Thread slow = Thread.ofPlatform().start(() -> call(s, "card-slow"));
            sleep(20);
            long t0 = System.nanoTime();
            call(s, "card-fast");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            slow.join();
            System.out.printf("2 independent  %-13s fast card answered in %d ms%n",
                    name, ms);
        }
    }

    static void ownerFailure() throws Exception {
        Partner p = new Partner();
        p.latencyMs.put("card-2", 50L);
        p.failOnce.add("card-2");
        SingleFlight s = new SingleFlight(p::fetch, 1_000);
        AtomicInteger failed = new AtomicInteger();
        burst(s, 20, i -> "card-2", failed);
        int callsAfterFailure = p.calls.get();
        int v = s.score("card-2");
        System.out.printf("3 failure      20 callers -> %d failed, %d partner call; "
                + "next call -> value %d, %d calls in total%n",
                failed.get(), callsAfterFailure, v, p.calls.get());
    }

    static void waiterTimeout() throws Exception {
        Partner p = new Partner();
        p.latencyMs.put("card-3", 1_000L);
        SingleFlight s = new SingleFlight(p::fetch, 200);
        Thread owner = Thread.ofPlatform().start(() -> call(s, "card-3"));
        sleep(20);
        long t0 = System.nanoTime();
        String outcome;
        try { s.score("card-3"); outcome = "value"; }
        catch (TimeoutException e) { outcome = "TimeoutException"; }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        owner.join();
        long t1 = System.nanoTime();
        s.score("card-3");
        long cachedUs = (System.nanoTime() - t1) / 1_000;
        System.out.printf("4 timeout      waiter -> %s after %d ms; owner's result "
                + "cached, next call %d us, %d partner call%n",
                outcome, ms, cachedUs, p.calls.get());
    }

    static void invalidation() throws Exception {
        Partner p = new Partner();
        p.latencyMs.put("card-4", 300L);
        SingleFlight s = new SingleFlight(p::fetch, 2_000);
        int[] old = new int[1];
        Thread owner = Thread.ofPlatform().start(() -> old[0] = call(s, "card-4"));
        sleep(50);
        p.version.set(2);
        s.invalidate("card-4");
        int fresh = s.score("card-4");
        owner.join();
        int after = s.score("card-4");
        System.out.printf("5 invalidation in-flight caller got %d (old), new caller "
                + "got %d, cache now holds %d, %d partner calls%n",
                old[0], fresh, after, p.calls.get());
    }

    static long burst(Scores s, int n, Function<Integer, String> key)
            throws Exception {
        return burst(s, n, key, new AtomicInteger());
    }

    static long burst(Scores s, int n, Function<Integer, String> key,
                      AtomicInteger failed) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            ts.add(Thread.ofPlatform().start(() -> {
                try { start.await(); s.score(key.apply(k)); }
                catch (Exception e) { failed.incrementAndGet(); }
            }));
        }
        long t0 = System.nanoTime();
        start.countDown();
        for (Thread t : ts) t.join();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static int call(Scores s, String c) {
        try { return s.score(c); } catch (Exception e) { return -1; }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
