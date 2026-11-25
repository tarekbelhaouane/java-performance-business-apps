package com.bank.connections;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public class StatementApi {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int errorEvery = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        byte[] ok = ("{\"account\":\"FR7630006000011234567890189\",\"lines\":["
                + "{\"date\":\"2026-11-02\",\"label\":\"CARD PAYMENT\",\"amount\":-42.10},".repeat(30)
                + "{\"date\":\"2026-11-03\",\"label\":\"SALARY\",\"amount\":2450.00}]}")
                .getBytes(StandardCharsets.UTF_8);
        byte[] notFound = "{\"error\":\"ACCOUNT_CLOSED\",\"detail\":\"no statement\"}".getBytes(StandardCharsets.UTF_8);
        AtomicLong n = new AtomicLong();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
        server.createContext("/statements", ex -> {
            boolean error = errorEvery > 0 && n.incrementAndGet() % errorEvery == 0;
            byte[] body = error ? notFound : ok;
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(error ? 404 : 200, body.length);
            try (var os = ex.getResponseBody()) { os.write(body); }
        });
        server.setExecutor(Executors.newFixedThreadPool(50));
        server.start();
        System.out.println("statement API on " + port + ", body " + ok.length + " bytes, 404 every " + errorEvery);
    }
}
