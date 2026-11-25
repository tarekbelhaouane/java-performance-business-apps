package com.bank.connections;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

public class ResponseLeakDemo {
    static int fds() { String[] l = new File("/proc/self/fd").list(); return l == null ? -1 : l.length; }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        int port = Integer.parseInt(args[1]);
        int calls = Integer.parseInt(args[2]);
        int errors = 0;
        int every = Math.min(200, calls / 10);
        System.out.printf("mode=%s, open file descriptors at start: %d%n", mode, fds());
        try {
            for (int i = 1; i <= calls; i++) {
                HttpURLConnection c = (HttpURLConnection) URI.create(
                        "http://127.0.0.1:" + port + "/statements/" + i).toURL().openConnection();
                c.setConnectTimeout(1000);
                c.setReadTimeout(2000);
                int status = c.getResponseCode();
                if (status != 200) errors++;
                if (mode.equals("close")) {
                    InputStream body = status == 200 ? c.getInputStream() : c.getErrorStream();
                    try (body) { if (body != null) body.readAllBytes(); }
                }

                if (i % every == 0 && fds() > 0)
                    System.out.printf("%6d calls, %4d errors, open file descriptors: %d%n", i, errors, fds());
            }
        } catch (Exception e) {
            System.out.printf("FAILED after %d errors, open file descriptors: %d%n  %s%n", errors, fds(), e);
        }
        if (args.length > 3) Thread.sleep(Long.parseLong(args[3]) * 1000);
        int before = fds();
        System.gc();
        Thread.sleep(1000);
        System.out.printf("before System.gc(): %d descriptors; after: %d%n", before, fds());
    }
}
