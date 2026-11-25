package com.bank.conn;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

final class Proc {
    static long fds() {
        try (Stream<Path> s = Files.list(Path.of("/proc/self/fd"))) {
            return s.count();
        } catch (Exception e) {
            return -1;
        }
    }

    static long rssMb() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status")))
                if (l.startsWith("VmRSS:")) return Long.parseLong(l.replaceAll("\\D", "")) / 1024;
        } catch (Exception ignored) { }
        return -1;
    }

    static java.util.Map<String, Long> tcpStates() {
        java.util.Map<String, String> names = java.util.Map.of("01", "ESTABLISHED", "05", "FIN_WAIT2",
                "06", "TIME_WAIT", "08", "CLOSE_WAIT", "0A", "LISTEN");
        java.util.Map<String, Long> counts = new java.util.TreeMap<>();
        try {
            for (String f : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
                java.util.List<String> lines = Files.readAllLines(Path.of(f));
                for (String l : lines.subList(1, lines.size())) {
                    String st = l.trim().split("\\s+")[3];
                    counts.merge(names.getOrDefault(st, st), 1L, Long::sum);
                }
            }
        } catch (Exception ignored) { }
        return counts;
    }

    static int threads() {
        return ManagementFactory.getThreadMXBean().getThreadCount();
    }

    static long heapMb(boolean gc) {
        if (gc) System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }
}
