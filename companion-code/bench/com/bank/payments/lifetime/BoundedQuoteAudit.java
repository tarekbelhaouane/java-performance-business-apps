package com.bank.payments.lifetime;

import com.bank.payments.quote.TransferQuote;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

public class BoundedQuoteAudit implements AutoCloseable {
    private static final int RECENT = 1_000;
    private static final int OUTBOX = 10_000;

    private final ArrayDeque<TransferQuote> recent = new ArrayDeque<>(RECENT);
    private final BlockingQueue<TransferQuote> outbox = new ArrayBlockingQueue<>(OUTBOX);
    private final LongAdder rejected = new LongAdder();
    private final Consumer<List<TransferQuote>> sink;
    private final Thread writer;
    private volatile boolean running = true;

    public BoundedQuoteAudit(Consumer<List<TransferQuote>> sink) {
        this.sink = sink;
        this.writer = new Thread(this::drain, "audit-writer");
        this.writer.start();
    }

    public void record(TransferQuote quote) throws InterruptedException {
        synchronized (recent) {
            if (recent.size() == RECENT) recent.removeFirst();
            recent.addLast(quote);
        }
        if (!outbox.offer(quote, 50, TimeUnit.MILLISECONDS)) {
            rejected.increment();
        }
    }

    public List<TransferQuote> lastQuotes() {
        synchronized (recent) { return List.copyOf(recent); }
    }

    public long rejected() { return rejected.sum(); }

    private void drain() {
        List<TransferQuote> batch = new ArrayList<>(500);
        try {
            while (running || !outbox.isEmpty()) {
                TransferQuote first = outbox.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                outbox.drainTo(batch, 499);
                sink.accept(batch);
                batch.clear();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close() throws InterruptedException {
        running = false;
        writer.join();
    }
}
