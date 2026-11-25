package com.bank.bench;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "card_transaction")
public class CardTransaction {
    @Id
    private Long id;
    @Column(name = "account_id")
    private Long accountId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id")
    private Merchant merchant;
    @Column(name = "booked_at")
    private LocalDateTime bookedAt;
    @Column(name = "amount_cents")
    private Long amountCents;
    private String label;

    public Long getId() { return id; }
    public Long getAccountId() { return accountId; }
    public Merchant getMerchant() { return merchant; }
    public LocalDateTime getBookedAt() { return bookedAt; }
    public Long getAmountCents() { return amountCents; }
    public String getLabel() { return label; }
}
