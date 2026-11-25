package com.bank.payments.memory;

import com.bank.payments.quote.*;

import java.lang.management.*;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import javax.management.ObjectName;

public class MemoryMapDemo {
    public interface PaymentHandler { void handle(); }

    static final List<TransferQuote> QUOTE_CACHE = new ArrayList<>();
    static final List<ByteBuffer> NETWORK_BUFFERS = new ArrayList<>();
    static final List<Object> PROXIES = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]);
        int directMb = Integer.parseInt(args[1]);
        int proxyClasses = Integer.parseInt(args[2]);

        QuoteService service = new QuoteService();
        for (int i = 0; i < 200_000; i++) {
            TransferRequest r = new TransferRequest("FR76...", "FR76...",
                    Money.eur(1_000 + i), (i & 1) == 0, Segment.STANDARD);
            QUOTE_CACHE.add(service.quote(r));
        }

        CountDownLatch stop = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> { try { stop.await(); } catch (InterruptedException e) { } },
                    "http-nio-8080-exec-" + (i + 1));
            t.setDaemon(true);
            t.start();
        }

        for (int i = 0; i < directMb; i++) {
            NETWORK_BUFFERS.add(ByteBuffer.allocateDirect(1024 * 1024));
        }

        for (int i = 0; i < proxyClasses; i++) {
            ClassLoader loader = new ClassLoader(MemoryMapDemo.class.getClassLoader()) { };
            PROXIES.add(Proxy.newProxyInstance(loader, new Class<?>[] { PaymentHandler.class },
                    (p, m, a) -> null));
        }

        System.gc();
        report();
        if (args.length > 3) nmt(args[3]);
    }

    static void report() throws Exception {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage();
        System.out.printf("Process RSS (resident)     : %6d MB%n", rssKb() / 1024);
        System.out.printf("Heap used / committed / max: %6d / %d / %d MB%n",
                heap.getUsed() >> 20, heap.getCommitted() >> 20, heap.getMax() >> 20);
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.NON_HEAP) {
                System.out.printf("  %-34s used %6.1f MB, committed %6.1f MB%n", p.getName(),
                        p.getUsage().getUsed() / 1048576.0, p.getUsage().getCommitted() / 1048576.0);
            }
        }
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            System.out.printf("  buffer pool %-22s %6.1f MB (%d buffers)%n", b.getName(),
                    b.getMemoryUsed() / 1048576.0, b.getCount());
        }
        System.out.printf("Live threads: %d, loaded classes: %d%n",
                ManagementFactory.getThreadMXBean().getThreadCount(),
                ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
    }

    static long rssKb() throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("\\D", ""));
        }
        return -1;
    }

    static void nmt(String mode) throws Exception {
        Object out = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                new Object[] { new String[] { mode } }, new String[] { String[].class.getName() });
        System.out.println(out);
    }
}
