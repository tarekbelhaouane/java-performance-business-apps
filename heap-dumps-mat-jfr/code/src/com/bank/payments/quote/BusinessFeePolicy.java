package com.bank.payments.quote;

import java.util.List;

public final class BusinessFeePolicy implements FeePolicy {
    @Override
    public List<FeeLine> fees(TransferRequest r) {
        Money fee = Money.eur(20).plus(r.amount().percentOf(5));
        return List.of(new FeeLine("Business fee", fee.atMost(Money.eur(800))));
    }
}
