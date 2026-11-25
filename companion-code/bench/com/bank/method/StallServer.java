package com.bank.method;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class StallServer {
    public static void main(String[] args) throws Exception {
        ReentrantReadWriteLock freeze = new ReentrantReadWriteLock();
        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(args[0])), 1000);
        server.setExecutor(Executors.newFixedThreadPool(200));
        server.createContext("/accounts/upcoming-payments", ex -> {
            freeze.readLock().lock();
            try { Thread.sleep(2); } catch (InterruptedException e) { return; } finally { freeze.readLock().unlock(); }
            ex.sendResponseHeaders(200, -1); ex.close();
        });
        server.start();
        long end = System.nanoTime() + Long.parseLong(args[1]) * 1_000_000_000L;
        while (System.nanoTime() < end) {
            Thread.sleep(9_000);
            freeze.writeLock().lock();
            Thread.sleep(1_000);
            freeze.writeLock().unlock();
        }
        System.exit(0);
    }
}
