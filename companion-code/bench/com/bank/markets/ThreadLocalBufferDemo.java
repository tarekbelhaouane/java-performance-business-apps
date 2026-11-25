package com.bank.markets;

import java.lang.management.ManagementFactory;
import java.util.concurrent.*;

public class ThreadLocalBufferDemo {
    static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[64 * 1024]);

    public static void main(String[] args) throws Exception {
        boolean virtual = args[0].equals("virtual");
        CountDownLatch encoded = new CountDownLatch(10_000);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService ex = virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(200);
        for (int i = 0; i < 10_000; i++) {
            ex.submit(() -> {
                byte[] buf = BUFFER.get();
                buf[0] = 1;
                encoded.countDown();
                release.await();
                return null;
            });
        }
        if (virtual) encoded.await(); else Thread.sleep(500);
        System.gc();
        long live = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        System.out.printf("%s: live heap while 10,000 messages wait for acknowledgement: %d MB%n", args[0], live);
        release.countDown();
        ex.shutdown();
    }
}
