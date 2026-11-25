package com.bank.payments.cards;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FraudScoring {
    private final Map<String, Integer> scores = new HashMap<>();
    private final boolean lockDuringRemoteCall;
    private final boolean singleFlight;
    static final int MAX_CARDS = 100_000;
    private final ConcurrentHashMap<String, CompletableFuture<Integer>> flights =
            new ConcurrentHashMap<>();

    public FraudScoring(boolean lockDuringRemoteCall) {
        this.lockDuringRemoteCall = lockDuringRemoteCall;
        this.singleFlight = false;
    }

    public FraudScoring(String mode) {
        this.lockDuringRemoteCall = mode.equals("true");
        this.singleFlight = mode.equals("v3");
    }

    public int score(String cardToken) {
        if (singleFlight) {
            return scoreSingleFlight(cardToken);
        }
        if (lockDuringRemoteCall) {
            return scoreWithLockAroundEverything(cardToken);
        }
        return scoreWithShortLock(cardToken);
    }

    private synchronized int scoreWithLockAroundEverything(String cardToken) {
        Integer cached = scores.get(cardToken);
        if (cached == null) {
            cached = RemoteScoringClient.fetchScore(cardToken);
            scores.put(cardToken, cached);
        }
        return cached;
    }

    private int scoreWithShortLock(String cardToken) {
        synchronized (this) {
            Integer cached = scores.get(cardToken);
            if (cached != null) {
                return cached;
            }
        }
        int fresh = RemoteScoringClient.fetchScore(cardToken);
        synchronized (this) {
            scores.putIfAbsent(cardToken, fresh);
        }
        return fresh;
    }

    private int scoreSingleFlight(String cardToken) {
        CompletableFuture<Integer> mine = new CompletableFuture<>();
        CompletableFuture<Integer> f = flights.putIfAbsent(cardToken, mine);
        if (f == null) {
            f = mine;
            try {
                mine.complete(RemoteScoringClient.fetchScore(cardToken));
            } catch (RuntimeException e) {
                flights.remove(cardToken, mine);
                mine.completeExceptionally(e);
            }
            if (flights.size() > MAX_CARDS) {
                Iterator<String> it = flights.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
        return f.join();
    }

    int cachedCards() { return singleFlight ? flights.size() : scores.size(); }
}
