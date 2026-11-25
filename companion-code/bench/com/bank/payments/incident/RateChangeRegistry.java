package com.bank.payments.incident;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RateChangeRegistry {
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    public static void register(Runnable listener)   { LISTENERS.add(listener); }
    public static void unregister(Runnable listener) { LISTENERS.remove(listener); }
    public static int size()                         { return LISTENERS.size(); }
}
