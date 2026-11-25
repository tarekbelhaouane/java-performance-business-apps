package com.bank.bench;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CardPageService {
    private final CardTransactionRepository repository;

    public CardPageService(CardTransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public long page(long accountId, String mode) {
        long sum = 0;
        switch (mode) {
            case "join" -> {
                for (CardTransaction t : repository.lastWithMerchant(accountId, PageRequest.of(0, 20)))
                    sum += t.getAmountCents() + t.getMerchant().getName().length();
            }
            case "dto" -> {
                for (CardLine l : repository.lines(accountId, PageRequest.of(0, 20)))
                    sum += l.amountCents() + l.merchantName().length();
            }
            default -> {
                for (CardTransaction t : repository.findTop20ByAccountIdOrderByBookedAtDesc(accountId))
                    sum += t.getAmountCents() + t.getMerchant().getName().length();
            }
        }
        return sum;
    }
}
