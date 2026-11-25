package com.bank.bench;

import java.lang.management.ManagementFactory;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bench")
public class DataBenchController {
    private final CardPageService pages;
    private final Statistics statistics;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DataBenchController(CardPageService pages, EntityManagerFactory emf, DataSource dataSource,
                               JdbcTemplate jdbc, TransactionTemplate tx) {
        this.pages = pages;
        this.statistics = emf.unwrap(SessionFactory.class).getStatistics();
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @GetMapping("/card-page")
    public Map<String, Object> cardPage(@RequestParam String mode, @RequestParam(defaultValue = "300") int pages) {
        for (int i = 0; i < 100; i++) this.pages.page(1 + i * 31L, mode);
        long statements0 = statistics.getPrepareStatementCount();
        long t0 = System.nanoTime(), check = 0;
        for (int p = 0; p < pages; p++) check += this.pages.page(1 + (p * 7919L) % 300_000, mode);
        double ms = (System.nanoTime() - t0) / 1e6 / pages;
        return Map.of("mode", mode, "msPerPage", round(ms), "statementsPerPage",
                (double) (statistics.getPrepareStatementCount() - statements0) / pages, "check", check);
    }

    @GetMapping("/card-page-distinct")
    public Map<String, Object> cardPageDistinct(@RequestParam String mode, @RequestParam int distinct,
                                                @RequestParam(defaultValue = "300") int pages) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 100; i++) ids.add(1 + i * 31L);
        for (int p = 0; p < pages; p++) ids.add(1 + (p * 7919L) % 300_000);
        Long[] accounts = ids.toArray(new Long[0]);
        int updated = jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "update card_transaction t set merchant_id = 1 + ((t.account_id * 97 + s.rn % ?) % 20000) "
                    + "from (select id, row_number() over (partition by account_id order by booked_at desc) - 1 "
                    + "as rn from card_transaction where account_id = any(?)) s where s.id = t.id");
            ps.setInt(1, distinct);
            ps.setArray(2, con.createArrayOf("bigint", accounts));
            return ps;
        });
        Integer check = jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement("select max(c) from (select count(distinct merchant_id) c "
                    + "from card_transaction where account_id = any(?) group by account_id) x");
            ps.setArray(1, con.createArrayOf("bigint", accounts));
            return ps;
        }, rs -> rs.next() ? rs.getInt(1) : -1);
        Map<String, Object> result = new LinkedHashMap<>(cardPage(mode, pages));
        result.put("distinctMerchantsPerPage", distinct);
        result.put("maxDistinctObserved", check);
        result.put("rowsRewritten", updated);
        return result;
    }

    @GetMapping("/rtt")
    public Map<String, Object> rtt(@RequestParam(defaultValue = "2000") int n) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("select 1")) {
            for (int i = 0; i < 200; i++) try (ResultSet rs = ps.executeQuery()) { rs.next(); }
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) try (ResultSet rs = ps.executeQuery()) { rs.next(); }
            return Map.of("statements", n, "meanMs", round((System.nanoTime() - t0) / 1e6 / n));
        }
    }

    @GetMapping("/pool")
    public Map<String, Object> pool(@RequestParam(defaultValue = "100") int threads,
                                    @RequestParam(defaultValue = "8") int seconds) throws Exception {
        LongAdder done = new LongAdder(), acquireNs = new LongAdder(), queryNs = new LongAdder();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                final int seed = t;
                ex.submit(() -> {
                    Random rnd = new Random(seed);
                    while (System.nanoTime() < end) {
                        long t0 = System.nanoTime();
                        try (Connection c = dataSource.getConnection()) {
                            long t1 = System.nanoTime();
                            try (PreparedStatement ps = c.prepareStatement("select t.booked_at, t.amount_cents, m.name "
                                    + "from card_transaction t join merchant m on m.id = t.merchant_id "
                                    + "where t.account_id = ? order by t.booked_at desc limit 20")) {
                                ps.setLong(1, 1 + rnd.nextInt(300_000));
                                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { rs.getString(3); } }
                            }
                            queryNs.add(System.nanoTime() - t1);
                            acquireNs.add(t1 - t0);
                            done.increment();
                        }
                    }
                    return null;
                });
            }
        }
        long n = done.sum();
        return Map.of("pagesPerSecond", n / seconds, "acquireMs", round(acquireNs.sum() / 1e6 / n),
                "queryMs", round(queryNs.sum() / 1e6 / n));
    }

    @GetMapping("/insert")
    public Map<String, Object> insert(@RequestParam String mode, @RequestParam(defaultValue = "20000") int rows) {
        jdbc.execute("truncate fee_posting");
        String sql = "insert into fee_posting values (?, ?, 50, 'Monthly card fee')";
        long t0 = System.nanoTime();
        switch (mode) {
            case "autocommit" -> { for (int i = 0; i < rows; i++) jdbc.update(sql, i, 1 + i % 300_000); }
            case "tx" -> tx.executeWithoutResult(s -> { for (int i = 0; i < rows; i++) jdbc.update(sql, i, 1 + i % 300_000); });
            default -> tx.executeWithoutResult(s -> {
                List<Object[]> args = new ArrayList<>();
                for (int i = 0; i < rows; i++) args.add(new Object[] {i, 1 + i % 300_000});
                jdbc.batchUpdate(sql, args);
            });
        }
        double s = (System.nanoTime() - t0) / 1e9;
        return Map.of("mode", mode, "seconds", round(s), "rowsPerSecond", Math.round(rows / s));
    }

    @GetMapping("/monthly-report")
    public Map<String, Object> monthlyReport(@RequestParam String mode) {
        String month = "t.booked_at >= timestamp '2026-03-01' and t.booked_at < timestamp '2026-04-01'";
        String select = "select m.category, t.amount_cents from card_transaction t join merchant m on m.id = t.merchant_id where " + month;
        Map<String, Long> totals = new TreeMap<>();
        long[] rows = {0};
        long[] liveMb = {0};
        long t0 = System.nanoTime();
        switch (mode) {
            case "load" -> {
                List<Map<String, Object>> all = jdbc.queryForList(select);
                rows[0] = all.size();
                liveMb[0] = liveHeapMb();
                for (Map<String, Object> r : all)
                    totals.merge((String) r.get("category"), ((Number) r.get("amount_cents")).longValue(), Long::sum);
            }
            case "stream" -> tx.executeWithoutResult(s -> {
                JdbcTemplate streaming = new JdbcTemplate(dataSource);
                streaming.setFetchSize(1000);
                streaming.query(select, (RowCallbackHandler) rs -> {
                    totals.merge(rs.getString(1), rs.getLong(2), Long::sum);
                    if (++rows[0] == 100_000) liveMb[0] = liveHeapMb();
                });
            });
            default -> {
                jdbc.query("select m.category, sum(t.amount_cents) from card_transaction t join merchant m "
                        + "on m.id = t.merchant_id where " + month + " group by m.category",
                        (RowCallbackHandler) rs -> totals.put(rs.getString(1), rs.getLong(2)));
                rows[0] = totals.size();
                liveMb[0] = liveHeapMb();
            }
        }
        return Map.of("mode", mode, "rows", rows[0], "ms", (System.nanoTime() - t0) / 1_000_000,
                "liveHeapMb", liveMb[0], "totals", totals);
    }

    static long liveHeapMb() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
