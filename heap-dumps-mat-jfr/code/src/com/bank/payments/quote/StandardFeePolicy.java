package com.bank.payments.quote;

import java.util.List;

public final class StandardFeePolicy implements FeePolicy {
    private static final long INSTANT_FEE_CENTS = 50;

    @Override
    public List<FeeLine> fees(TransferRequest r) {
        Money fee = r.amount().percentOf(10).atMost(Money.eur(500));
        if (!r.instant()) {
            return List.of(new FeeLine("Transfer fee", fee));
        }
        return List.of(new FeeLine("Transfer fee", fee),
                new FeeLine("Instant option", Money.eur(INSTANT_FEE_CENTS)));
    }
}
