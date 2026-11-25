package com.bank.conn;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/bench/jersey")
public class JerseyBenchController {
    private final String partner;

    public JerseyBenchController(@Value("${partner.url}") String partner) {
        this.partner = partner;
    }

    @GetMapping(value = "/leak", produces = "text/plain")
    public String leak(@RequestParam String mode, @RequestParam(defaultValue = "5000") int calls,
                       @RequestParam(defaultValue = "200") int every,
                       @RequestParam(defaultValue = "0") int hold) throws Exception {
        StringBuilder out = new StringBuilder("jersey default connector, mode=" + mode + "\n");
        out.append("start: descriptors ").append(Proc.fds()).append('\n');
        Client client = ClientBuilder.newClient();
        WebTarget consents = client.target(partner).path("partner/consents/{id}/status");
        int i = 0;
        try {
            for (i = 1; i <= calls; i++) {
                if (mode.equals("leak")) {
                    Response r = consents.resolveTemplate("id", i).request().get();
                    if (r.getStatus() != 200) out.append("unexpected status\n");
                } else {
                    try (Response r = consents.resolveTemplate("id", i).request().get()) {
                        if (r.getStatus() != 200) out.append("unexpected status\n");
                    }
                }
                if (i % every == 0) out.append(String.format("%6d calls, descriptors %d%n", i, Proc.fds()));
            }
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            out.append("FAILED at call ").append(i).append(": ").append(e.getClass().getSimpleName())
               .append(" caused by ").append(root).append('\n');
        }
        if (hold > 0) {
            Thread.sleep(hold * 1000L);
            out.append("after ").append(hold).append(" s: descriptors ").append(Proc.fds())
               .append("; TCP states in this network namespace ").append(Proc.tcpStates()).append('\n');
        }
        long before = Proc.fds();
        System.gc();
        Thread.sleep(1000);
        out.append("before System.gc(): ").append(before).append(" descriptors; after: ").append(Proc.fds())
           .append('\n');
        client.close();
        return out.toString();
    }

    @GetMapping(value = "/pool", produces = "text/plain")
    public String pool(@RequestParam String mode, @RequestParam(defaultValue = "8") int threads,
                       @RequestParam(defaultValue = "15") int seconds,
                       @RequestParam(defaultValue = "20") int max,
                       @RequestParam(defaultValue = "100") int errorEvery) throws Exception {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(max);
        cm.setDefaultMaxPerRoute(max);
        ClientConfig config = new ClientConfig()
                .connectorProvider(new ApacheConnectorProvider())
                .property(ApacheClientProperties.CONNECTION_MANAGER, cm)
                .property(ClientProperties.CONNECT_TIMEOUT, 2000)
                .property(ClientProperties.READ_TIMEOUT, 5000);
        if (mode.equals("leak-timeout"))
            config.property(ApacheClientProperties.REQUEST_CONFIG,
                    RequestConfig.custom().setConnectionRequestTimeout(2000).build());
        Client client = ClientBuilder.newClient(config);
        WebTarget balances = client.target(partner).path("partner/balances/{id}")
                .queryParam("errorEvery", errorEvery);
        AtomicLong done = new AtomicLong(), notFound = new AtomicLong(), errors = new AtomicLong(),
                ids = new AtomicLong();
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) ex.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    long id = ids.incrementAndGet();
                    if (mode.equals("fixed")) {
                        try (Response r = balances.resolveTemplate("id", id).request().get()) {
                            if (r.getStatus() == 404) notFound.incrementAndGet();
                            else r.readEntity(String.class);
                        }
                    } else {
                        Response r = balances.resolveTemplate("id", id).request().get();
                        if (r.getStatus() == 404) { notFound.incrementAndGet(); continue; }
                        r.readEntity(String.class);
                    }
                    done.incrementAndGet();
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
        });
        StringBuilder out = new StringBuilder("jersey apache connector, pool of " + max + ", mode=" + mode + "\n");
        long last = 0;
        for (int s = 1; s <= seconds; s++) {
            Thread.sleep(1000);
            long d = done.get();
            var st = cm.getTotalStats();
            out.append(String.format(Locale.ROOT,
                    "t=%2ds requests/s=%4d 404 so far=%3d leased=%2d/%d pending=%2d errors=%d%n",
                    s, d - last, notFound.get(), st.getLeased(), max, st.getPending(), errors.get()));
            last = d;
        }
        ex.shutdownNow();
        cm.shutdown();
        client.close();
        return out.toString();
    }

    @GetMapping(value = "/clients", produces = "text/plain")
    public String clients(@RequestParam String mode, @RequestParam(defaultValue = "2000") int calls) {
        Client shared = ClientBuilder.newClient();
        for (int i = 0; i < 200; i++) call(shared, i);
        long maxThreads = 0, maxFds = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            switch (mode) {
                case "shared" -> call(shared, i);
                case "new" -> call(ClientBuilder.newClient(), i);
                case "new-closed" -> { Client c = ClientBuilder.newClient(); call(c, i); c.close(); }
                default -> throw new IllegalArgumentException(mode);
            }
            if (i % 50 == 0) { maxThreads = Math.max(maxThreads, Proc.threads()); maxFds = Math.max(maxFds, Proc.fds()); }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        shared.close();
        return String.format(Locale.ROOT, "jersey %-10s %d calls: %.2f ms per call; threads max %d; descriptors max %d; heap %d MB%n",
                mode, calls, ms / calls, maxThreads, maxFds, Proc.heapMb(true));
    }

    private void call(Client c, int i) {
        try (Response r = c.target(partner).path("partner/balances/{id}").resolveTemplate("id", i + 1)
                .queryParam("errorEvery", 0).request().get()) {
            r.readEntity(String.class);
        }
    }
}
