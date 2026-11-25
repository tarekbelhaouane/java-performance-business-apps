package com.bank.method;

import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;

public class ClosedLoopGenerator {
    public static void main(String[] args) throws Exception {
        URI uri = URI.create("http://localhost:" + args[0] + "/accounts/upcoming-payments");
        int users = Integer.parseInt(args[1]);
        long think = Long.parseLong(args[2]);
        long end = System.nanoTime() + Long.parseLong(args[3]) * 1_000_000_000L;
        HttpClient client = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        List<Long> lat = Collections.synchronizedList(new ArrayList<>());
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int u = 0; u < users; u++) {
                ex.submit(() -> {
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.discarding());
                        lat.add(System.nanoTime() - t0);
                        Thread.sleep(think);
                    }
                    return null;
                });
            }
        }
        long[] a = lat.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("closed loop: requests=%,d (%d/s) p50=%d ms p99=%d ms p99.9=%d ms max=%d ms%n", a.length,
                a.length / Long.parseLong(args[3]), a[a.length / 2] / 1_000_000, a[(int) (a.length * 0.99)] / 1_000_000,
                a[(int) (a.length * 0.999)] / 1_000_000, a[a.length - 1] / 1_000_000);
    }
}
