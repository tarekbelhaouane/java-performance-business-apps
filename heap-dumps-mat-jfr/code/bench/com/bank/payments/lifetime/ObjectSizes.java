package com.bank.payments.lifetime;

import com.bank.payments.quote.*;
import java.util.ArrayList;
import java.util.List;

public class ObjectSizes {
    static final List<TransferQuote> KEEP = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        QuoteService service = new QuoteService();
        for (int i = 0; i < 100_000; i++) {
            KEEP.add(service.quote(new TransferRequest("FR7630006000011234567890189",
                    "FR7610107001011234567890129", Money.eur(10_000 + i), true, Segment.STANDARD)));
        }
        System.out.println("ready " + KEEP.size());
        Thread.sleep(Long.MAX_VALUE);
    }
}
