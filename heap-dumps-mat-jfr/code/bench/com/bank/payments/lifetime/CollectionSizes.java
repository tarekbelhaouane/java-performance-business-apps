package com.bank.payments.lifetime;

import com.bank.payments.quote.Money;
import java.util.*;

public class CollectionSizes {
    static Map<String, Money> balancesByIban = new HashMap<>();
    static List<Long> balancesAsBoxedLongs = new ArrayList<>();
    static long[] balancesAsLongs = new long[100_000];

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 100_000; i++) {
            String iban = String.format("FR76300060000%014d", i);
            long cents = 1_000_000L + i * 37L;
            balancesByIban.put(iban, Money.eur(cents));
            balancesAsBoxedLongs.add(cents);
            balancesAsLongs[i] = cents;
        }
        System.out.println("ready");
        Thread.sleep(Long.MAX_VALUE);
    }
}
