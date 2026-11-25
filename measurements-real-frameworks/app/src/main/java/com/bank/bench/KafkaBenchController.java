package com.bank.bench;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/bench/kafka")
public class KafkaBenchController {
    private final KafkaTemplate<Long, String> kafka;
    private final KafkaListenerEndpointRegistry registry;
    private final JdbcTemplate jdbc;
    private final RestClient http = RestClient.create();
    private final String pushUrl;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong slowestPollMs = new AtomicLong();
    private final AtomicLong polls = new AtomicLong();
    private final AtomicLong firstRecordNs = new AtomicLong();

    public KafkaBenchController(KafkaTemplate<Long, String> kafka, KafkaListenerEndpointRegistry registry,
                                JdbcTemplate jdbc, @Value("${server.port}") int port,
                                @Value("${bench.push-delay-ms}") long pushDelayMs) {
        this.kafka = kafka;
        this.registry = registry;
        this.jdbc = jdbc;
        this.pushUrl = "http://localhost:" + port + "/stub/push?delayMs=" + pushDelayMs;
    }

    @GetMapping("/produce")
    public Map<String, Object> produce(@RequestParam(defaultValue = "100000") int events) {
        long t0 = System.nanoTime();
        for (long i = 0; i < events; i++) {
            long account = 1 + (i * 7) % 3000;
            kafka.send("payment-events", account, account + "," + (1000 + i % 900));
        }
        kafka.flush();
        return Map.of("events", events, "seconds", (System.nanoTime() - t0) / 1e9);
    }

    @GetMapping("/consume")
    public Map<String, Object> consume(@RequestParam String mode, @RequestParam int events,
                                       @RequestParam(defaultValue = "900") int timeoutSeconds) throws Exception {
        processed.set(0);
        slowestPollMs.set(0);
        jdbc.execute("truncate notification_log");
        String id = mode.equals("batch") ? "notif-batch" : "notif-seq";
        polls.set(0);
        firstRecordNs.set(0);
        long t0 = System.nanoTime();
        registry.getListenerContainer(id).start();
        while (processed.get() < events && System.nanoTime() - t0 < timeoutSeconds * 1_000_000_000L) Thread.sleep(50);
        long t1 = System.nanoTime();
        long counted = processed.get();
        registry.getListenerContainer(id).stop();
        double s = (t1 - t0) / 1e9;
        double active = firstRecordNs.get() == 0 ? s : (t1 - firstRecordNs.get()) / 1e9;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", mode);
        r.put("processedAtClockStop", counted);
        r.put("processedAfterStop", processed.get());
        r.put("seconds", Math.round(s * 10) / 10.0);
        r.put("secondsFromFirstRecord", Math.round(active * 10) / 10.0);
        r.put("eventsPerSecondPerThread", Math.round(counted / s));
        r.put("eventsPerSecondFromFirstRecord", Math.round(counted / active));
        r.put("polls", polls.get());
        r.put("slowestPollMs", slowestPollMs.get());
        return r;
    }

    @KafkaListener(id = "notif-seq", topics = "payment-events", groupId = "notif-seq", autoStartup = "false")
    public void onEvent(ConsumerRecord<Long, String> event) {
        firstRecordNs.compareAndSet(0, System.nanoTime());
        http.get().uri(pushUrl).retrieve().toBodilessEntity();
        String[] f = event.value().split(",");
        jdbc.update("insert into notification_log values (?, ?, ?)", event.key(), Long.parseLong(f[0]), Long.parseLong(f[1]));
        processed.incrementAndGet();
    }

    @KafkaListener(id = "notif-batch", topics = "payment-events", groupId = "notif-batch", autoStartup = "false",
                   batch = "true")
    public void onPoll(List<ConsumerRecord<Long, String>> events) throws Exception {
        long t0 = System.nanoTime();
        firstRecordNs.compareAndSet(0, t0);
        polls.incrementAndGet();
        Map<Long, List<ConsumerRecord<Long, String>>> byAccount = new LinkedHashMap<>();
        for (ConsumerRecord<Long, String> e : events) byAccount.computeIfAbsent(e.key(), k -> new ArrayList<>()).add(e);
        Semaphore inFlight = new Semaphore(50);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (List<ConsumerRecord<Long, String>> perAccount : byAccount.values()) {
                ex.submit(() -> {
                    for (ConsumerRecord<Long, String> e : perAccount) {
                        inFlight.acquire();
                        try { http.get().uri(pushUrl).retrieve().toBodilessEntity(); } finally { inFlight.release(); }
                    }
                    return null;
                });
            }
        }
        List<Object[]> rows = new ArrayList<>();
        for (ConsumerRecord<Long, String> e : events) {
            String[] f = e.value().split(",");
            rows.add(new Object[] {e.key(), Long.parseLong(f[0]), Long.parseLong(f[1])});
        }
        jdbc.batchUpdate("insert into notification_log values (?, ?, ?)", rows);
        slowestPollMs.accumulateAndGet((System.nanoTime() - t0) / 1_000_000, Math::max);
        processed.addAndGet(events.size());
    }
}
