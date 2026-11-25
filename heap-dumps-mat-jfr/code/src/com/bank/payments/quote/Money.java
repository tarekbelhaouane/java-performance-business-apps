package com.bank.payments.quote;

public record Money(long cents, String currency) {
    public static Money eur(long cents) {
        return new Money(cents, "EUR");
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(cents + other.cents, currency);
    }

    public Money percentOf(int basisPoints) {
        return new Money(cents * basisPoints / 10_000, currency);
    }

    public Money atMost(Money cap) {
        requireSameCurrency(cap);
        return cents <= cap.cents ? this : cap;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch");
        }
    }
}
