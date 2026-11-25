package com.bank.payments.quote;

import java.util.List;

public final class YouthFeePolicy implements FeePolicy {
    @Override
    public List<FeeLine> fees(TransferRequest r) {
        return List.of();
    }
}
