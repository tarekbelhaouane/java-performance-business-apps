package com.bank.payments.quote;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class QuoteService {
    private final Map<Segment, FeePolicy> policies = new EnumMap<>(Segment.class);

    public QuoteService() {
        policies.put(Segment.STANDARD, new StandardFeePolicy());
        policies.put(Segment.PREMIUM, new PremiumFeePolicy());
        policies.put(Segment.YOUTH, new YouthFeePolicy());
        policies.put(Segment.BUSINESS, new BusinessFeePolicy());
    }

    public TransferQuote quote(TransferRequest request) {
        List<FeeLine> fees = policies.get(request.segment()).fees(request);
        Money total = request.amount();
        for (FeeLine line : fees) {
            total = total.plus(line.amount());
        }
        return new TransferQuote(request.amount(), fees, total);
    }
}
