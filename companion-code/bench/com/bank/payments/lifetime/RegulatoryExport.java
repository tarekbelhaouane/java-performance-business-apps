package com.bank.payments.lifetime;

import com.bank.payments.quote.*;

import java.io.Writer;
import java.lang.management.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class RegulatoryExport {
    static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    static void writeWithFormat(Writer out, TransferQuote q, String iban, LocalDate day) throws Exception {
        String amount = String.format(Locale.ROOT, "%.2f", q.amount().cents() / 100.0);
        String fees = String.format(Locale.ROOT, "%.2f", (q.totalDebited().cents() - q.amount().cents()) / 100.0);
        String line = String.join(";", day.format(DAY), iban.substring(0, 4) + "****" + iban.substring(iban.length() - 4),
                amount, fees, q.amount().currency());
        out.write(line + "\n");
    }

    static void writeWithBuilder(Writer out, StringBuilder sb, TransferQuote q, String iban, String day) throws Exception {
        sb.setLength(0);
        sb.append(day).append(';')
          .append(iban, 0, 4).append("****").append(iban, iban.length() - 4, iban.length()).append(';');
        appendCents(sb, q.amount().cents()).append(';');
        appendCents(sb, q.totalDebited().cents() - q.amount().cents()).append(';');
        sb.append(q.amount().currency()).append('\n');
        out.append(sb);
    }

    static StringBuilder appendCents(StringBuilder sb, long cents) {
        if (cents < 0) {
            sb.append('-');
            if (cents == Long.MIN_VALUE) {
                return sb.append("92233720368547758.08");
            }
            cents = -cents;
        }
        long c = cents % 100;
        sb.append(cents / 100).append('.');
        if (c < 10) sb.append('0');
        return sb.append(c);
    }

    public static void main(String[] args) throws Exception {
        boolean builder = args[0].equals("builder");
        int lines = Integer.parseInt(args[1]);
        QuoteService service = new QuoteService();
        String iban = "FR7630006000011234567890189";
        LocalDate day = LocalDate.of(2026, 11, 3);
        String dayText = day.format(DAY);
        Writer out = Writer.nullWriter();
        StringBuilder sb = new StringBuilder(128);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        for (int pass = 0; pass < 2; pass++) {
            long gcCount0 = gcCount(), gcTime0 = gcTime();
            long b0 = mx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            long t0 = System.nanoTime();
            for (int i = 0; i < lines; i++) {
                TransferQuote q = service.quote(new TransferRequest(iban, iban,
                        Money.eur(1_000 + (i % 5_000_000)), (i & 1) == 0, Segment.STANDARD));
                if (builder) writeWithBuilder(out, sb, q, iban, dayText);
                else writeWithFormat(out, q, iban, day);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long bytes = mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) - b0;
            if (pass == 1) {
                MemoryUsage old = oldGenAfterGc();
                System.out.printf("%s: %d lines in %d ms, %.0f bytes/line, %.0f MB/s allocated, %d young GCs, " +
                                "GC time %d ms, old gen after last GC %d MB%n",
                        args[0], lines, ms, bytes / (double) lines, bytes / 1048576.0 / (ms / 1000.0),
                        gcCount() - gcCount0, gcTime() - gcTime0, old == null ? -1 : old.getUsed() >> 20);
            }
        }
    }

    static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum(); }
    static long gcTime() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum(); }
    static MemoryUsage oldGenAfterGc() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getName().contains("Old Gen")) return p.getCollectionUsage();
        return null;
    }
}
