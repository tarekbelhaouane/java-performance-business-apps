package com.bank.payments.cards;

import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;

public class ThreadStatesDemo {
    static final Object ACCOUNT_A = new Object();
    static final Object ACCOUNT_B = new Object();

    public static void main(String[] args) throws Exception {
        ServerSocket silentPartner = new ServerSocket(0);
        start("partner-call-1", () -> {
            try (Socket s = new Socket("localhost", silentPartner.getLocalPort())) {
                InputStream in = s.getInputStream();
                in.read();
            }
            return null;
        });

        start("reconciliation-1", () -> {
            long x = 0;
            while (true) { x += System.nanoTime() % 7; if (x == -1) break; }
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(3, r -> new Thread(r, "notification-worker"));
        for (int i = 0; i < 3; i++) pool.submit(() -> { });

        start("transfer-A-to-B", () -> { transfer(ACCOUNT_A, ACCOUNT_B); return null; });
        start("transfer-B-to-A", () -> { transfer(ACCOUNT_B, ACCOUNT_A); return null; });

        Thread.sleep(Long.MAX_VALUE);
    }

    static void transfer(Object from, Object to) throws InterruptedException {
        synchronized (from) {
            Thread.sleep(100);
            synchronized (to) {
            }
        }
    }

    static void start(String name, Callable<Void> body) {
        Thread t = new Thread(() -> { try { body.call(); } catch (Exception e) { } }, name);
        t.setDaemon(true);
        t.start();
    }
}
