package com.bank.payments.container;

import com.bank.payments.quote.*;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.Executors;

public class QuoteHttpServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int perRequest = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        String cg = args[3];
        QuoteService service = new QuoteService();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 100);
        server.setExecutor(Executors.newFixedThreadPool(50));
        server.createContext("/transfers/preview", ex -> {
            long total = 0;
            for (int i = 0; i < perRequest; i++) {
                TransferQuote q = service.quote(new TransferRequest("FR7630006000011234567890189",
                        "DE89370400440532013000", Money.eur(10_000 + i), (i & 1) == 0, Segment.BUSINESS));
                total += q.totalDebited().cents();
            }
            byte[] body = ("{\"transfers\":" + perRequest + ",\"totalDebited\":" + total + "}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        Thread.sleep(seconds * 1000L);
        double cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                .getProcessCpuTime() / 1e9;
        System.out.printf("availableProcessors=%d process CPU=%.1f s%n",
                Runtime.getRuntime().availableProcessors(), cpu);
        System.out.println(Files.readString(Path.of("/sys/fs/cgroup/cpu/" + cg + "/cpu.stat")).replace('\n', ' '));
        server.stop(0);
        System.exit(0);
    }
}
