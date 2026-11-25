package com.bank.bench;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
public class WebController {
    private final JdbcTemplate jdbc;
    private final UpcomingPaymentsService upcoming;

    public WebController(JdbcTemplate jdbc, UpcomingPaymentsService upcoming) {
        this.jdbc = jdbc;
        this.upcoming = upcoming;
    }

    @GetMapping("/accounts/gateway")
    public Map<String, Object> gateway(@RequestParam long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
        return Map.of("account", "FR76...0189", "upcoming", List.of(Map.of("label", "Rent", "amount", "850.00")));
    }

    @GetMapping("/accounts/db-wait")
    public Map<String, Object> dbWait(@RequestParam long queryMs) {
        jdbc.execute("select pg_sleep(" + (queryMs / 1000.0) + ")");
        return Map.of("status", "ok");
    }

    @GetMapping("/accounts/upcoming-payments")
    public List<Map<String, Object>> upcomingPayments(@RequestParam(defaultValue = "long") String idType) {
        return upcoming.upcoming(idType.equals("decimal"));
    }

    @GetMapping("/stub/push")
    public void push(@RequestParam long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }
}
