package com.bank.payments.quote;

public record TransferRequest(String fromIban, String toIban, Money amount,
                              boolean instant, Segment segment) {
}
