package com.bank.payments.lifetime;

import java.math.BigDecimal;
import java.util.*;

public final class MoneyFormatCheck {
    static String viaDouble(long cents) {
        return String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }
    static String firstEdition(long cents) {
        StringBuilder sb = new StringBuilder();
        sb.append(cents / 100).append('.');
        long c = cents % 100;
        if (c < 10) sb.append('0');
        return sb.append(c).toString();
    }
    static String fixed(long cents) {
        return RegulatoryExport.appendCents(new StringBuilder(), cents)
                .toString();
    }
    static String exact(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    public static void main(String[] args) {
        System.out.println("JDK " + Runtime.version());
        long[] edges = {0, 1, -1, 5, -5, 99, -99, 100, -100, 150, -150,
                125_000, -125_000, 1L << 53, (1L << 53) + 1,
                Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1};
        System.out.printf("%-22s %-24s %-24s %-24s %s%n",
                "cents", "exact", "via double", "previous", "fixed");
        for (long c : edges) {
            System.out.printf("%-22d %-24s %-24s %-24s %s%n", c, exact(c),
                    viaDouble(c), firstEdition(c), fixed(c));
        }
        Random r = new Random(42);
        long[][] ranges = {
                {-100_000_000_000L, 100_000_000_000L},
                {1_000_000_000_000_000L, 9_000_000_000_000_000L}};
        for (long[] range : ranges) {
            int n = 5_000_000, d = 0, e = 0, f = 0;
            long firstDouble = 0;
            for (int i = 0; i < n; i++) {
                long c = range[0] + (long) (r.nextDouble()
                        * ((double) range[1] - range[0]));
                String x = exact(c);
                if (!viaDouble(c).equals(x)) { if (d++ == 0) firstDouble = c; }
                if (!firstEdition(c).equals(x)) e++;
                if (!fixed(c).equals(x)) f++;
            }
            System.out.printf("range [%d, %d], %,d values: via double %,d "
                    + "differ%s, previous %,d differ, fixed %,d differ%n",
                    range[0], range[1], n, d,
                    d == 0 ? "" : " (e.g. " + firstDouble + " -> "
                            + viaDouble(firstDouble) + " vs "
                            + exact(firstDouble) + ")", e, f);
        }
    }
}
