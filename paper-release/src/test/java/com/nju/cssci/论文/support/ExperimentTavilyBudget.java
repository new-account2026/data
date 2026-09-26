package com.nju.cssci.论文.support;

import com.nju.cssci.论文.PaperExperimentModeGate;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayList;
import java.time.Instant;

/** Thread-safe hard reservation gate checked immediately before a real Tavily request. */
public final class ExperimentTavilyBudget {
    private final int limit;
    private final AtomicInteger actualCalls = new AtomicInteger();
    private final AtomicInteger exceeded = new AtomicInteger();
    private final AtomicInteger successful = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger retryCalls = new AtomicInteger();
    private final AtomicBoolean warning100 = new AtomicBoolean();
    private final AtomicBoolean warning50 = new AtomicBoolean();
    private final AtomicBoolean warning0 = new AtomicBoolean();
    private final List<Reservation> reservations = java.util.Collections.synchronizedList(new ArrayList<>());

    public ExperimentTavilyBudget(int limit) {
        if (limit < 0 || limit > PaperExperimentModeGate.MAX_TAVILY_HARD_LIMIT) {
            throw new IllegalArgumentException("Tavily budget must be 0..800");
        }
        this.limit = limit;
    }

    /** A successful reservation is itself counted as a real request attempt. */
    public boolean tryReserveRealCall() {
        return tryReserveRealCall(null, null, 1);
    }

    /** Must be called immediately before each physical provider attempt, including every retry. */
    public boolean tryReserveRealCall(String logicalRequestId, String requestHash, int physicalAttempt) {
        while (true) {
            int current = actualCalls.get();
            if (current >= limit) {
                exceeded.incrementAndGet();
                reservations.add(new Reservation(logicalRequestId, requestHash, physicalAttempt,
                        false, current, Instant.now().toString(), null));
                return false;
            }
            if (actualCalls.compareAndSet(current, current + 1)) {
                if (physicalAttempt > 1) retryCalls.incrementAndGet();
                reservations.add(new Reservation(logicalRequestId, requestHash, physicalAttempt,
                        true, current + 1, Instant.now().toString(), null));
                emitWarnings(limit - current - 1);
                return true;
            }
        }
    }

    public void recordOutcome(boolean success) {
        if (success) successful.incrementAndGet(); else failed.incrementAndGet();
    }

    private void emitWarnings(int remaining) {
        if (remaining <= 100 && warning100.compareAndSet(false, true)) {
            System.out.println("TAVILY_BUDGET_WARNING: remaining physical calls=" + remaining);
        }
        if (remaining <= 50 && warning50.compareAndSet(false, true)) {
            System.out.println("TAVILY_BUDGET_HIGH_RISK: remaining physical calls=" + remaining);
        }
        if (remaining == 0 && warning0.compareAndSet(false, true)) {
            System.out.println("TAVILY_BUDGET_EXHAUSTED: no further provider request is permitted");
        }
    }

    public int limit() { return limit; }
    public int actualCalls() { return actualCalls.get(); }
    public int remaining() { return Math.max(0, limit - actualCalls()); }
    public int exceededCount() { return exceeded.get(); }
    public int successfulCalls() { return successful.get(); }
    public int failedCalls() { return failed.get(); }
    public int retryCalls() { return retryCalls.get(); }
    public List<Reservation> reservations() { return List.copyOf(reservations); }

    public record Reservation(String logicalRequestId, String requestHash, int physicalAttempt,
                              boolean granted, int consumedAfterReservation, String timestamp,
                              String note) { }
}
