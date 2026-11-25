package com.bank.accounts.web;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class SharedFormatterDemo {
    static final class LegacyDueDateService {
        private final SimpleDateFormat format = new SimpleDateFormat("dd/MM/yyyy");
        private long calls;
        String dueDate(Date d) { calls++; return format.format(d); }
    }

    static final class DueDateService {
        private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        private final AtomicLong calls = new AtomicLong();
        String dueDate(LocalDate d) { calls.incrementAndGet(); return FORMAT.format(d); }
    }

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]);
        int perThread = Integer.parseInt(args[1]);
        LegacyDueDateService legacy = new LegacyDueDateService();
        DueDateService fixed = new DueDateService();
        AtomicLong wrong = new AtomicLong(), errors = new AtomicLong(), wrongFixed = new AtomicLong();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                final int day = 1 + t % 28;
                pool.submit(() -> {
                    Calendar c = new GregorianCalendar(2026, Calendar.NOVEMBER, day);
                    Date date = c.getTime();
                    String expected = String.format("%02d/11/2026", day);
                    LocalDate local = LocalDate.of(2026, 11, day);
                    for (int i = 0; i < perThread; i++) {
                        try {
                            if (!legacy.dueDate(date).equals(expected)) wrong.incrementAndGet();
                        } catch (RuntimeException e) { errors.incrementAndGet(); }
                        if (!fixed.dueDate(local).equals(expected)) wrongFixed.incrementAndGet();
                    }
                });
            }
        }
        long total = (long) threads * perThread;
        System.out.printf("calls=%,d%n", total);
        System.out.printf("SimpleDateFormat shared : wrong dates=%,d exceptions=%,d counter=%,d (lost %,d)%n",
                wrong.get(), errors.get(), legacy.calls, total - legacy.calls);
        System.out.printf("DateTimeFormatter       : wrong dates=%,d counter=%,d%n",
                wrongFixed.get(), fixed.calls.get());
    }
}
