package com.bank.payments.cards;

public final class CardAuthorisationService {
    private final FraudScoring fraudScoring;

    public CardAuthorisationService(FraudScoring fraudScoring) {
        this.fraudScoring = fraudScoring;
    }

    public boolean authorise(String cardToken, long amountInCents) {
        int score = fraudScoring.score(cardToken);
        return score < 900 && amountInCents < 300_000;
    }
}
