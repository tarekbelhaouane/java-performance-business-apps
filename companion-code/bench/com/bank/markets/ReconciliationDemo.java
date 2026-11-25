package com.bank.markets;

import java.util.*;

public class ReconciliationDemo {
    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        List<String> authorisations = new ArrayList<>();
        for (int i = 0; i < n; i++) authorisations.add("AUTH-" + (1_000_000 + i));
        List<String> clearing = new ArrayList<>(authorisations);
        Collections.shuffle(clearing, new Random(42));

        long t0 = System.nanoTime(), matched = 0;
        for (String c : clearing) if (authorisations.contains(c)) matched++;
        long t1 = System.nanoTime();
        Set<String> index = new HashSet<>(authorisations);
        long matched2 = 0;
        for (String c : clearing) if (index.contains(c)) matched2++;
        long t2 = System.nanoTime();
        System.out.printf("%,d records: List.contains %,d ms, HashSet %,d ms (%d/%d matched)%n",
                n, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, matched, matched2);
    }
}
