package com.bank.payments.incident;

import com.bank.payments.lifetime.QuoteAuditService;
import com.bank.payments.quote.*;
import java.lang.management.ManagementFactory;

public class AuditIncident {
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args[0]);
        QuoteService service = new QuoteService();
        QuoteAuditService audit = new QuoteAuditService();
        String iban = "FR7630006000011234567890189";
        long start = System.nanoTime();
        long next = 5;
        for (long i = 0; ; i++) {
            long intended = start + (long) (i * (1e9 / rate));
            long now;
            while ((now = System.nanoTime()) < intended) { Thread.sleep(0, 200_000); }
            TransferQuote q = service.quote(new TransferRequest(iban, iban,
                    Money.eur(1_000 + (i % 100_000)), (i & 1) == 0, Segment.STANDARD));
            audit.record(q);
            if ((now - start) / 1_000_000_000L >= next) {
                long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
                System.out.printf("t=%3ds quotes=%,d heap used=%d MB%n", next, i + 1, used);
                next += 5;
            }
        }
    }
}
