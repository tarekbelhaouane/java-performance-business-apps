package com.bank.bench;

import java.lang.management.ManagementFactory;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/bench")
public class BatchBenchController {
    record AccountRow(long id, String iban, long balanceCents, long cardTotal, int lines) { }

    static final List<AccountRow> PROCESSED = Collections.synchronizedList(new ArrayList<>());

    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public BatchBenchController(JobRepository jobRepository, JobLauncher jobLauncher,
                                PlatformTransactionManager transactionManager, DataSource dataSource, JdbcTemplate jdbc) {
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @GetMapping("/batch")
    public Map<String, Object> run(@RequestParam String mode, @RequestParam(defaultValue = "30000") long accounts,
                                   @RequestParam(defaultValue = "100") int chunk,
                                   @RequestParam(defaultValue = "false") boolean keep) throws Exception {
        jdbc.execute("truncate monthly_statement");
        PROCESSED.clear();
        String month = "booked_at >= timestamp '2026-03-01' and booked_at < timestamp '2026-04-01'";

        JdbcPagingItemReader<AccountRow> reader = new JdbcPagingItemReaderBuilder<AccountRow>()
                .name("accounts").dataSource(dataSource).pageSize(chunk)
                .selectClause("select id, iban, balance_cents").fromClause("from account").whereClause("where id <= :max")
                .parameterValues(Map.<String, Object>of("max", accounts)).sortKeys(Map.of("id", Order.ASCENDING))
                .rowMapper((rs, i) -> new AccountRow(rs.getLong(1), rs.getString(2), rs.getLong(3), 0, 0))
                .build();
        reader.afterPropertiesSet();

        ItemProcessor<AccountRow, AccountRow> processor = mode.equals("item")
                ? a -> jdbc.queryForObject("select coalesce(sum(amount_cents), 0), count(*) from card_transaction "
                        + "where account_id = ? and " + month,
                        (rs, i) -> new AccountRow(a.id(), a.iban(), a.balanceCents(), rs.getLong(1), rs.getInt(2)), a.id())
                : a -> a;

        ItemWriter<AccountRow> writer = (Chunk<? extends AccountRow> items) -> {
            List<AccountRow> rows = new ArrayList<>(items.getItems());
            if (mode.equals("chunk")) {
                List<Long> ids = rows.stream().map(AccountRow::id).toList();
                Map<Long, long[]> sums = new HashMap<>();
                named.query("select account_id, sum(amount_cents), count(*) from card_transaction "
                        + "where account_id in (:ids) and " + month + " group by account_id",
                        new MapSqlParameterSource("ids", ids),
                        (RowCallbackHandler) rs -> { sums.put(rs.getLong(1), new long[] {rs.getLong(2), rs.getLong(3)}); });
                rows.replaceAll(a -> {
                    long[] s = sums.getOrDefault(a.id(), new long[2]);
                    return new AccountRow(a.id(), a.iban(), a.balanceCents(), s[0], (int) s[1]);
                });
            }
            List<Object[]> args = new ArrayList<>();
            for (AccountRow a : rows) {
                args.add(new Object[] {a.id(), "2026-03", a.balanceCents(), a.cardTotal(),
                        a.balanceCents() * 25 / 10_000 / 12, a.lines()});
                if (keep) PROCESSED.add(a);
            }
            jdbc.batchUpdate("insert into monthly_statement values (?, ?, ?, ?, ?, ?)", args);
        };

        Step step = new StepBuilder("statementStep", jobRepository)
                .<AccountRow, AccountRow>chunk(chunk, transactionManager)
                .reader(reader).processor(processor).writer(writer).build();
        Job job = new JobBuilder("statementJob", jobRepository).start(step).build();

        long t0 = System.nanoTime();
        JobExecution execution = jobLauncher.run(job, new JobParametersBuilder()
                .addLong("run", System.nanoTime()).toJobParameters());
        double s = (System.nanoTime() - t0) / 1e9;
        Integer written = jdbc.queryForObject("select count(*) from monthly_statement", Integer.class);
        System.gc();
        long live = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        return Map.of("mode", mode, "chunk", chunk, "keep", keep, "status", execution.getStatus().toString(),
                "seconds", Math.round(s * 100) / 100.0, "accountsPerSecond", Math.round(written / s),
                "liveHeapMb", live, "exitDescription", String.valueOf(execution.getExitStatus().getExitDescription()));
    }
}
