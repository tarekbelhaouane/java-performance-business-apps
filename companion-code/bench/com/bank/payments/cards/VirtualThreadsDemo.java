package com.bank.payments.cards;

import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public class VirtualThreadsDemo {
    public static void main(String[] args) throws Exception {
        boolean useSynchronized = args[0].equals("synchronized");
        long t0 = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 200; i++) {
                Object monitor = new Object();
                ReentrantLock lock = new ReentrantLock();
                executor.submit(() -> {
                    for (int k = 0; k < 5; k++) {
                        if (useSynchronized) {
                            synchronized (monitor) {
                                RemoteScoringClient.fetchScore("card");
                            }
                        } else {
                            lock.lock();
                            try {
                                RemoteScoringClient.fetchScore("card");
                            } finally {
                                lock.unlock();
                            }
                        }
                    }
                    return null;
                });
            }
        }
        System.out.printf("%s: %d ms for 1,000 fraud checks on 200 virtual threads%n",
                args[0], (System.nanoTime() - t0) / 1_000_000);
    }
}
