package com.bank.payments.lifetime;

import com.bank.payments.quote.*;
import java.lang.management.ManagementFactory;

public class AuditLeakDemo {
    public static void main(String[] args) throws Exception {
        int millions = Integer.parseInt(args[0]);
        QuoteService service = new QuoteService();
        QuoteAuditService audit = new QuoteAuditService();
        String iban = "FR7630006000011234567890189";
        for (int m = 1; m <= millions; m++) {
            for (int i = 0; i < 1_000_000; i++) {
                TransferQuote q = service.quote(new TransferRequest(iban, iban,
                        Money.eur(1_000 + i), (i & 1) == 0, Segment.STANDARD));
                audit.record(q);
            }
            System.gc();
            long live = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            System.out.printf("%d million quotes: live heap after full GC %d MB, audit list %d entries%n",
                    m, live >> 20, audit.size());
        }
        if (args.length > 1) Thread.sleep(Long.MAX_VALUE);
    }
}
