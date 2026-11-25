package com.bank.payments.incident;

import com.bank.payments.lifetime.BoundedQuoteAudit;
import com.bank.payments.quote.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.LongAdder;

public class AuditIncidentFixed {
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args[0]);
        long seconds = Long.parseLong(args[1]);
        QuoteService service = new QuoteService();
        LongAdder written = new LongAdder();
        String iban = "FR7630006000011234567890189";
        try (BoundedQuoteAudit audit = new BoundedQuoteAudit(b -> written.add(b.size()))) {
            long start = System.nanoTime(), next = 5;
            for (long i = 0; ; i++) {
                long intended = start + (long) (i * (1e9 / rate));
                long now;
                while ((now = System.nanoTime()) < intended) { Thread.sleep(0, 200_000); }
                if ((now - start) / 1_000_000_000L >= seconds) break;
                audit.record(service.quote(new TransferRequest(iban, iban,
                        Money.eur(1_000 + (i % 100_000)), (i & 1) == 0, Segment.STANDARD)));
                if ((now - start) / 1_000_000_000L >= next) {
                    long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
                    System.out.printf("t=%3ds quotes=%,d written=%,d in memory=%d rejected=%d heap used=%d MB%n",
                            next, i + 1, written.sum(), audit.lastQuotes().size(), audit.rejected(), used);
                    next += 5;
                }
            }
            System.out.println("ready for dump");
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
