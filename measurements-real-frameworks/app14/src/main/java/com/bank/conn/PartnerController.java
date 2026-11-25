package com.bank.conn;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/partner")
public class PartnerController {
    private static final String BALANCE = "{\"account\":\"FR7630006000011234567890189\",\"balances\":["
            + "{\"type\":\"closingBooked\",\"amount\":\"2450.00\",\"currency\":\"EUR\"},".repeat(20)
            + "{\"type\":\"expected\",\"amount\":\"2450.00\",\"currency\":\"EUR\"}]}";

    @GetMapping("/consents/{id}/status")
    public String consent(@PathVariable long id) {
        return "{\"consentStatus\":\"valid\"}";
    }

    @GetMapping("/balances/{id}")
    public ResponseEntity<String> balance(@PathVariable long id,
                                          @RequestParam(defaultValue = "100") long errorEvery) {
        if (errorEvery > 0 && id % errorEvery == 0)
            return ResponseEntity.status(404).body("{\"error\":\"ACCOUNT_CLOSED\"}");
        return ResponseEntity.ok(BALANCE);
    }
}
