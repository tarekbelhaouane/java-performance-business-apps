package com.bank.payments.quote;

public final class QuoteBatch {
    private final QuoteService service = new QuoteService();

    public long quoteAll(TransferRequest[] requests) {
        long sum = 0;
        for (TransferRequest r : requests) {
            sum += service.quote(r).totalDebited().cents();
        }
        return sum;
    }
}
