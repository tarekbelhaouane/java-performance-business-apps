package com.bank.payments.lifetime;

import com.bank.payments.quote.TransferQuote;
import java.util.ArrayList;
import java.util.List;

public class QuoteAuditService {
    private final List<TransferQuote> recentQuotes = new ArrayList<>();

    public synchronized void record(TransferQuote quote) {
        recentQuotes.add(quote);
    }

    public synchronized int size() {
        return recentQuotes.size();
    }
}
