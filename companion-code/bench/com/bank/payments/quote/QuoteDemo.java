package com.bank.payments.quote;

public class QuoteDemo {
    public static void main(String[] args) {
        QuoteService service = new QuoteService();
        TransferRequest r = new TransferRequest("FR7630006000011234567890189",
                "FR7610107001011234567890129", Money.eur(125_000), true, Segment.STANDARD);
        System.out.println(service.quote(r));
    }
}
