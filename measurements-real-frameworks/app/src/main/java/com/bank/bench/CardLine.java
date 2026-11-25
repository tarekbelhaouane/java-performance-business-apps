package com.bank.bench;

import java.time.LocalDateTime;

public record CardLine(LocalDateTime bookedAt, Long amountCents, String merchantName, String category) { }
