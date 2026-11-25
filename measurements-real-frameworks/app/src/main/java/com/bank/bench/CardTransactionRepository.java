package com.bank.bench;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {
    List<CardTransaction> findTop20ByAccountIdOrderByBookedAtDesc(Long accountId);

    @Query("select t from CardTransaction t join fetch t.merchant where t.accountId = :accountId order by t.bookedAt desc")
    List<CardTransaction> lastWithMerchant(@Param("accountId") Long accountId, Pageable page);

    @Query("select new com.bank.bench.CardLine(t.bookedAt, t.amountCents, m.name, m.category) "
            + "from CardTransaction t join t.merchant m where t.accountId = :accountId order by t.bookedAt desc")
    List<CardLine> lines(@Param("accountId") Long accountId, Pageable page);
}
