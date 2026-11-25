package com.bank.payments.quote;

import java.util.List;

public final class PremiumFeePolicy implements FeePolicy {
    @Override
    public List<FeeLine> fees(TransferRequest r) {
        return r.instant()
                ? List.of(new FeeLine("Instant option", Money.eur(25)))
                : List.of();
    }
}
