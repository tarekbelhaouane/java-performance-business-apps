package com.bank.conn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

@Component
public class QuoteStreamHandler extends TextWebSocketHandler {
    static final String[] SYMBOLS = {"EURUSD", "GBPUSD", "USDJPY", "USDCHF", "EURGBP",
                                     "AUDUSD", "USDCAD", "NZDUSD", "EURJPY", "EURCHF"};
    private final boolean fixed;
    private final String delivery;
    private final int heartbeat;
    final Map<String, Set<WebSocketSession>> bySymbol = new ConcurrentHashMap<>();
    final Map<String, WebSocketSession> open = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> senders = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Set<String> limited = ConcurrentHashMap.newKeySet();
    final LongAdder frames = new LongAdder(), closedByLimit = new LongAdder(), closedByHeartbeat = new LongAdder(),
            sendFailed = new LongAdder();
    private final double[] mid = new double[SYMBOLS.length];
    private final Random rnd = new Random(42);

    public QuoteStreamHandler(@Value("${ws.mode}") String mode, @Value("${ws.delivery}") String delivery,
                              @Value("${ws.heartbeat}") int heartbeat) {
        this.fixed = mode.equals("fixed");
        this.delivery = delivery;
        this.heartbeat = heartbeat;
        Arrays.fill(mid, 1.08);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession s) {
        open.put(s.getId(), s);
        lastSeen.put(s.getId(), System.nanoTime());
        WebSocketSession sender = switch (delivery) {
            case "decorator" -> new ConcurrentWebSocketSessionDecorator(s, 2_000, 256 * 1024,
                    ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
            case "queue" -> new ConcurrentWebSocketSessionDecorator(s, Integer.MAX_VALUE, Integer.MAX_VALUE);
            default -> s;
        };
        senders.put(s.getId(), sender);
    }

    @Override
    protected void handleTextMessage(WebSocketSession s, TextMessage m) {
        lastSeen.put(s.getId(), System.nanoTime());
        String[] cmd = m.getPayload().split(" ");
        if (cmd.length < 2) return;
        Set<WebSocketSession> subs = bySymbol.computeIfAbsent(cmd[1], k -> ConcurrentHashMap.newKeySet());
        if (cmd[0].equals("SUB")) subs.add(s);
        else if (cmd[0].equals("UNSUB")) subs.remove(s);
    }

    @Override
    protected void handlePongMessage(WebSocketSession s, PongMessage m) {
        lastSeen.put(s.getId(), System.nanoTime());
    }

    @Override
    public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
        if (fixed && s.isOpen()) s.close(CloseStatus.SERVER_ERROR);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
        open.remove(s.getId());
        senders.remove(s.getId());
        lastSeen.remove(s.getId());
        if (fixed) bySymbol.values().forEach(subs -> subs.remove(s));
    }

    @Scheduled(fixedRate = 20)
    public void broadcast() {
        long ts = System.nanoTime();
        for (int i = 0; i < SYMBOLS.length; i++) {
            Set<WebSocketSession> subs = bySymbol.get(SYMBOLS[i]);
            if (subs == null || subs.isEmpty()) continue;
            mid[i] += (rnd.nextDouble() - 0.5) * 1e-4;
            StringBuilder book = new StringBuilder(640);
            for (int l = 1; l <= 10; l++)
                book.append(String.format(Locale.ROOT, "[%.5f,%d,%.5f,%d],",
                        mid[i] - l * 1e-5, l * 1_000_000, mid[i] + l * 1e-5, l * 1_000_000));
            String body = book.substring(0, book.length() - 1);
            for (WebSocketSession s : subs) {
                if (!s.isOpen()) continue;
                TextMessage msg = new TextMessage("{\"sub\":\"" + s.getId() + "-" + SYMBOLS[i]
                        + "\",\"s\":\"" + SYMBOLS[i] + "\",\"ts\":" + ts + ",\"book\":[" + body + "]}");
                send(s, msg);
            }
        }
    }

    private void send(WebSocketSession s, WebSocketMessage<?> msg) {
        WebSocketSession sender = senders.getOrDefault(s.getId(), s);
        if (delivery.equals("sync")) {
            try {
                synchronized (s) { s.sendMessage(msg); }
                frames.increment();
            } catch (IOException | IllegalStateException e) {
                sendFailed.increment();
            }
        } else {
            Thread.startVirtualThread(() -> {
                try {
                    sender.sendMessage(msg);
                    frames.increment();
                } catch (Exception e) {
                    if (e.getClass().getSimpleName().equals("SessionLimitExceededException")) {
                        if (limited.add(s.getId())) {
                            closedByLimit.increment();
                            try { s.close(CloseStatus.SESSION_NOT_RELIABLE); } catch (Exception ignored) { }
                        }
                    } else sendFailed.increment();
                }
            });
        }
    }

    @Scheduled(fixedRate = 1000)
    public void heartbeat() {
        if (heartbeat <= 0) return;
        long now = System.nanoTime();
        if ((now / 1_000_000_000L) % heartbeat != 0) return;
        for (WebSocketSession s : open.values()) {
            Long seen = lastSeen.get(s.getId());
            try {
                if (seen != null && now - seen > 3L * heartbeat * 1_000_000_000L) {
                    closedByHeartbeat.increment();
                    s.close(CloseStatus.SESSION_NOT_RELIABLE);
                } else {
                    synchronized (s) { s.sendMessage(new PingMessage()); }
                }
            } catch (Exception ignored) { }
        }
    }
}
