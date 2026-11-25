package com.bank.payments.gc;

import com.bank.payments.quote.*;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.Arrays;

public class GcWorkload {
    public static void main(String[] args) throws Exception {
        int seconds = Integer.parseInt(args[0]);
        QuoteService service = new QuoteService();
        TransferQuote[] recent = new TransferQuote[1_500_000];
        String iban = "FR7630006000011234567890189";
        for (int i = 0; i < recent.length; i++) {
            recent[i] = service.quote(new TransferRequest(iban, iban, Money.eur(1_000 + i), true, Segment.STANDARD));
        }
        int rate = Integer.parseInt(args[1]);
        int total = seconds * rate;
        long[] lat = new long[total];
        long sink = 0;
        int slot = 0;
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        for (int phase = 0; phase < 2; phase++) {
            int count = phase == 0 ? 3 * rate : total;
            long start = System.nanoTime();
            long cpu0 = os.getProcessCpuTime();
            long gc0 = gcThreadCpuMillis();
            if (phase == 1) System.out.printf("measure-start uptime=%.3fs%n", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
            for (int i = 0; i < count; i++) {
                long intended = start + (long) (i * (1e9 / rate));
                while (System.nanoTime() < intended) { Thread.onSpinWait(); }
                TransferQuote q = service.quote(new TransferRequest(iban, iban, Money.eur(1_000 + slot), true, Segment.STANDARD));
                byte[] response = new byte[16 * 1024];
                response[slot & 1023] = (byte) q.totalDebited().cents();
                recent[slot] = q;
                slot = (slot + 1) % recent.length;
                sink += response[slot & 1023];
                if (phase == 1) lat[i] = System.nanoTime() - intended;
            }
            if (phase == 1) {
                System.out.printf("measure-end uptime=%.3fs%n", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
                long wall = System.nanoTime() - start;
                long cpuUsed = os.getProcessCpuTime() - cpu0;
                long gcCpu = gcThreadCpuMillis() - gc0;
                Arrays.sort(lat);
                System.out.printf("rate=%d/s  p50=%.0fus p99=%.0fus p99.9=%.0fus max=%.1fms  GC threads CPU=%d ms (%d)%n",
                        rate, lat[total / 2] / 1e3, lat[(int) (total * 0.99)] / 1e3, lat[(int) (total * 0.999)] / 1e3,
                        lat[total - 1] / 1e6, gcCpu, sink);
            }
        }
    }

    static long gcThreadCpuMillis() {
        long ticks = 0;
        try (var tasks = Files.list(Path.of("/proc/self/task"))) {
            for (Path t : (Iterable<Path>) tasks::iterator) {
                String stat = Files.readString(t.resolve("stat"));
                String name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'));
                if (name.startsWith("GC Thread") || name.startsWith("G1") || name.startsWith("Z")
                        || name.equals("VM Thread")) {
                    String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                    ticks += Long.parseLong(f[11]) + Long.parseLong(f[12]);
                }
            }
        } catch (Exception e) { return -1; }
        return ticks * 10;
    }
}
