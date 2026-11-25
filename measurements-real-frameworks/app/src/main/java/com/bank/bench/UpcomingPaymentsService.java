package com.bank.bench;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpcomingPaymentsService {
    private final JdbcTemplate jdbc;

    public UpcomingPaymentsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> upcoming(boolean decimalId) {
        long customer = 1 + ThreadLocalRandom.current().nextInt(200_000);
        List<Long> accounts = jdbc.queryForList("select id from account where customer_id = ?", Long.class, customer);
        List<Map<String, Object>> debits = new ArrayList<>();
        for (Long account : accounts) {
            Object id = decimalId ? new BigDecimal(account) : account;
            debits.addAll(jdbc.queryForList("select due_date, amount_cents, beneficiary from scheduled_payment "
                    + "where account_id = ? and status = 'PLANNED' "
                    + "and due_date between date '2026-11-25' and date '2026-12-24' order by due_date", id));
        }
        return debits;
    }
}
