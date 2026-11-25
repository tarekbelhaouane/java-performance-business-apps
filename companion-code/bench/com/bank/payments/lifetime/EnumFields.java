package com.bank.payments.lifetime;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public class EnumFields {
    enum Currency { EUR, USD, GBP }
    enum Status { PENDING, SETTLED, REJECTED }
    record CachedTransaction(long id, long cents, Currency currency, Status status) { }

    public static void main(String[] args) throws Exception {
        List<CachedTransaction> cache = new ArrayList<>();
        for (int i = 0; i < 1_000_000; i++) {
            cache.add(new CachedTransaction(i, 1_000 + i,
                    Currency.valueOf(new String("EUR".toCharArray())),
                    Status.valueOf(new String("SETTLED".toCharArray()))));
        }
        for (int i = 0; i < 5; i++) { System.gc(); Thread.sleep(500); }
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        System.out.printf("live heap after GC: %d MB (%d cached transactions)%n", used >> 20, cache.size());
    }
}
