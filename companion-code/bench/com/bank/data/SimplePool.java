package com.bank.data;

import java.util.concurrent.*;

public final class SimplePool implements AutoCloseable {
    private final BlockingQueue<PgWire> idle;
    private final long timeoutMs;

    public SimplePool(int size, int port, long timeoutMs) throws Exception {
        idle = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) idle.add(new PgWire("localhost", port, "postgres", "norvane"));
        this.timeoutMs = timeoutMs;
    }

    public PgWire borrow() throws Exception {
        PgWire c = idle.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (c == null) throw new TimeoutException("Connection is not available, request timed out after "
                + timeoutMs + "ms");
        return c;
    }

    public void release(PgWire c) { idle.add(c); }

    @Override public void close() throws Exception { for (PgWire c : idle) c.close(); }
}
