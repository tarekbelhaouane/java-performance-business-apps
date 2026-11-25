package com.bank.payments.container;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

public class PodMemoryDemo {
    public static void main(String[] args) throws Exception {
        int stepMb = Integer.parseInt(args[0]);
        int steps = Integer.parseInt(args[1]);
        int directMb = Integer.parseInt(args[2]);
        List<ByteBuffer> direct = new ArrayList<>();
        for (int i = 0; i < directMb; i++) {
            ByteBuffer b = ByteBuffer.allocateDirect(1 << 20);
            for (int p = 0; p < b.capacity(); p += 4096) b.put(p, (byte) 1);
            direct.add(b);
        }
        long max = Runtime.getRuntime().maxMemory() >> 20;
        System.out.printf("max heap %d MB, direct buffers %d MB, RSS %d MB%n", max, directMb, rssMb());
        List<byte[]> cache = new ArrayList<>();
        for (int s = 1; s <= steps; s++) {
            for (int i = 0; i < stepMb * 4; i++) {
                byte[] chunk = new byte[1 << 18];
                Arrays.fill(chunk, (byte) s);
                cache.add(chunk);
            }
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
            System.out.printf("step %2d: cached %4d MB, heap used %4d MB, RSS %4d MB%n", s, s * stepMb, used, rssMb());
            Thread.sleep(300);
        }
        System.out.println("done");
    }

    static long rssMb() throws Exception {
        for (String l : Files.readAllLines(Path.of("/proc/self/status")))
            if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) >> 10;
        return -1;
    }
}
