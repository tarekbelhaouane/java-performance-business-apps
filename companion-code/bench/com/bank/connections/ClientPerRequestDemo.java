package com.bank.connections;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ClientPerRequestDemo {
    static int fds() { return new File("/proc/self/fd").list().length; }
    static HttpClient newClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        int calls = Integer.parseInt(args[2]);
        var threads = ManagementFactory.getThreadMXBean();
        HttpClient shared = newClient();
        for (int i = 0; i < 200; i++) call(shared, port, i);
        int maxThreads = 0, maxFds = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            switch (mode) {
                case "shared" -> call(shared, port, i);
                case "new" -> call(newClient(), port, i);
                case "new-closed" -> { try (HttpClient c = newClient()) { call(c, port, i); } }
                default -> throw new IllegalArgumentException(mode);
            }
            if (i % 50 == 0) {
                maxThreads = Math.max(maxThreads, threads.getThreadCount());
                maxFds = Math.max(maxFds, fds());
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%-10s %d calls in %.0f ms: %.2f ms per call; threads max %d, now %d; descriptors max %d, now %d%n",
                mode, calls, ms, ms / calls, maxThreads, threads.getThreadCount(), maxFds, fds());
    }

    static void call(HttpClient c, int port, int i) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/statements/" + i))
                .timeout(Duration.ofSeconds(2)).build();
        c.send(r, HttpResponse.BodyHandlers.ofByteArray());
    }
}
