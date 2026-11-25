package com.bank.payments.cards;

import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class AuthorisationLoad {
    public static void main(String[] args) throws Exception {
        int workers = Integer.parseInt(args[0]);
        String mode = args[1];
        int seconds = Integer.parseInt(args[2]);
        FraudScoring scoring = new FraudScoring(mode);
        CardAuthorisationService service = new CardAuthorisationService(scoring);
        AtomicLong done = new AtomicLong();
        AtomicLong cardSeq = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "auth-worker-" + cardSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        AtomicLong card = new AtomicLong();
        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                while (System.nanoTime() < end) {
                    service.authorise("card-" + card.incrementAndGet(), 4_500);
                    done.incrementAndGet();
                }
            });
        }
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpu0 = os.getProcessCpuTime();
        long t0 = System.nanoTime();
        Thread.sleep(seconds * 1000L);
        long wall = System.nanoTime() - t0;
        long cpu = os.getProcessCpuTime() - cpu0;
        System.out.printf("workers=%d mode=%s  authorisations/s=%.0f  process CPU=%.1f%% of one core  cached cards=%,d%n",
                workers, mode, done.get() / (wall / 1e9), 100.0 * cpu / wall, scoring.cachedCards());
    }
}
