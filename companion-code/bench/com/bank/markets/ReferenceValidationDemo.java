package com.bank.markets;

import java.util.regex.Pattern;

public class ReferenceValidationDemo {
    static final Pattern NESTED = Pattern.compile("^([A-Z0-9]+\\s?)+$");
    static final Pattern LINEAR = Pattern.compile("^[A-Z0-9]+(?:\\s[A-Z0-9]+)*$");

    public static void main(String[] args) {
        System.out.println("valid: " + NESTED.matcher("INV 2026 11 0042").matches() + " / "
                + LINEAR.matcher("INV 2026 11 0042").matches());
        for (int n = 16; n <= 28; n += 2) {
            String bad = "A".repeat(n) + "!";
            long t0 = System.nanoTime();
            boolean m1 = NESTED.matcher(bad).matches();
            long t1 = System.nanoTime();
            boolean m2 = LINEAR.matcher(bad).matches();
            long t2 = System.nanoTime();
            System.out.printf("length %2d: version 1 %,10.1f ms   version 2 %.3f ms   (%b %b)%n",
                    n + 1, (t1 - t0) / 1e6, (t2 - t1) / 1e6, m1, m2);
        }
    }
}
