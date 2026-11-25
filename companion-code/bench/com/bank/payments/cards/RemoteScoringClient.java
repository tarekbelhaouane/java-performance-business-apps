package com.bank.payments.cards;

final class RemoteScoringClient {
    static int fetchScore(String cardToken) {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.abs(cardToken.hashCode() % 1000);
    }
}
