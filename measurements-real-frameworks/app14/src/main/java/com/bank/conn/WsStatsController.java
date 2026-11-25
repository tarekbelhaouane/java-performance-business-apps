package com.bank.conn;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@RestController
public class WsStatsController {
    private final QuoteStreamHandler h;
    private long lastFrames;

    public WsStatsController(QuoteStreamHandler h) {
        this.h = h;
    }

    @GetMapping(value = "/bench/ws/stats", produces = "text/plain")
    public String stats(@RequestParam(defaultValue = "false") boolean gc) {
        Set<WebSocketSession> registered = new HashSet<>();
        h.bySymbol.values().forEach(registered::addAll);
        long closedRegistered = registered.stream().filter(s -> !s.isOpen()).count();
        long f = h.frames.sum();
        long delta = f - lastFrames;
        lastFrames = f;
        return String.format(Locale.ROOT,
                "open=%d registered=%d closedButRegistered=%d heap %s=%d MB rss=%d MB threads=%d fds=%d "
                + "framesSinceLast=%d closedByLimit=%d closedByHeartbeat=%d sendFailed=%d%n",
                h.open.size(), registered.size(), closedRegistered, gc ? "live" : "used", Proc.heapMb(gc),
                Proc.rssMb(), Proc.threads(), Proc.fds(), delta, h.closedByLimit.sum(),
                h.closedByHeartbeat.sum(), h.sendFailed.sum());
    }
}
