package com.bank.accounts.web;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class LoadGenerator {
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        String path = args.length > 3 ? args[3] : "/accounts/upcoming-payments";
        URI uri = URI.create("http://localhost:" + args[2] + path);
        HttpClient client = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor())
                .connectTimeout(Duration.ofSeconds(5)).build();
        int total = rate * seconds;
        long[] lat = new long[total];
        int[] status = new int[total];
        long start = System.nanoTime() + 200_000_000L;
        try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                final int k = i;
                long intended = start + (long) (i * (1e9 / rate));
                long wait = intended - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                senders.submit(() -> {
                    HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
                    try {
                        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                        status[k] = r.statusCode();
                    } catch (Exception e) {
                        status[k] = -1;
                    }
                    lat[k] = System.nanoTime() - intended;
                });
            }
        }
        int window = rate * 5;
        for (int w = 0; w < total / window; w++) {
            long[] part = Arrays.copyOfRange(lat, w * window, (w + 1) * window);
            int errors = 0;
            for (int i = w * window; i < (w + 1) * window; i++) if (status[i] != 200) errors++;
            Arrays.sort(part);
            System.out.printf("sent %2d-%2ds  p50=%6d ms  p99=%6d ms  failed=%5.1f %%%n", w * 5, w * 5 + 5,
                    part[part.length / 2] / 1_000_000, part[(int) (part.length * 0.99)] / 1_000_000,
                    100.0 * errors / window);
        }
        long[] all = lat.clone();
        Arrays.sort(all);
        long ok = Arrays.stream(status).filter(s -> s == 200).count();
        System.out.printf("TOTAL p50=%d ms p99=%d ms max=%d ms ok=%.1f %%%n", all[total / 2] / 1_000_000,
                all[(int) (total * 0.99)] / 1_000_000, all[total - 1] / 1_000_000, 100.0 * ok / total);
    }
}
