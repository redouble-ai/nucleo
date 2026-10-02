/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Accounts, jobs and helpers shared by the admission tests. Every account here is a real
 * {@link RateLimiter} under the non-blocking contract, so the monitor is exercised exactly as
 * production exercises it; only the resources behind the counters are fake.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class AdmissionFixtures {

    private AdmissionFixtures() {
    }

    /** A resource-free job whose only purpose is to own a {@link JobContext}. */
    static final class StubJob extends AbstractJob<String> {
        StubJob() {
            super(Job.workflow("admission-test", "admission-test"), "stub");
        }

        @Override
        public JobRequirements getRequirements() {
            return new JobRequirements();
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) {
            return "stub";
        }
    }

    static JobContext<String> context() {
        return new JobContext<>(new StubJob(), "tester", Duration.ofMinutes(1), null);
    }

    @SafeVarargs
    static Demand demand(RateLimiter<Void>... limiters) {
        Demand demand = new Demand();
        for (RateLimiter<Void> limiter : limiters) {
            demand.add(limiter, null);
        }
        return demand;
    }

    /** The memory predicate: clear unless a test latches it. */
    static final class MemoryFake extends AbstractRateLimiter<Void> {
        volatile boolean clear = true;
        final AtomicInteger gives = new AtomicInteger();

        @Override
        public boolean fits(List<Void> mine, List<Void> reservedAhead) {
            return clear;
        }

        @Override
        public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
            return clear;
        }

        @Override
        public void give(List<Void> amounts) {
            gives.incrementAndGet();
            capacityChanged();
        }

        @Override
        public Long earliestFit(List<Void> mine, List<Void> reservedAhead) {
            return null;
        }

        void wake() {
            capacityChanged();
        }

        @Override
        public String limiterName() {
            return "memory";
        }

        @Override
        public String limiterCategory() {
            return "memory";
        }

        @Override
        public long capacity() {
            return 1;
        }

        @Override
        public long currentInUse() {
            return clear ? 0 : 1;
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> clear ? "clear" : "latched";
        }
    }

    /** A unit-permit gate with a name. */
    static final class Slots extends CountingGate {
        private final String name;

        Slots(String name, int capacity) {
            super(capacity);
            this.name = name;
        }

        @Override
        public String limiterName() {
            return name;
        }
    }

    /**
     * A clocked TIME account: a level that refills at a rate against an injectable clock, so a
     * test can drain it, ask for the deadline, advance the clock and wake the evaluator.
     */
    static final class Meter extends AbstractRateLimiter<Integer> {
        private final String name;
        private final int cap;
        private final double perNano;
        private final LongSupplier nanos;
        private double level;
        private long last;
        final AtomicInteger takes = new AtomicInteger();
        final AtomicInteger gives = new AtomicInteger();

        Meter(String name, int cap, double perSecond, LongSupplier nanos) {
            this.name = name;
            this.cap = cap;
            this.perNano = perSecond / 1_000_000_000.0;
            this.nanos = nanos;
            this.level = cap;
            this.last = nanos.getAsLong();
        }

        synchronized void drain() {
            refresh();
            level = 0;
        }

        synchronized double level() {
            refresh();
            return level;
        }

        void wake() {
            capacityChanged();
        }

        private void refresh() {
            long now = nanos.getAsLong();
            level = Math.min(cap, level + (now - last) * perNano);
            last = now;
        }

        private static int sum(List<Integer> amounts) {
            int total = 0;
            for (Integer amount : amounts) {
                total += amount;
            }
            return total;
        }

        private void refuseIfImpossible(int needed) {
            if (needed > cap) {
                throw new UncorrectableRuntimeLLMException(name + ": " + needed + " can never fit a cap of " + cap);
            }
        }

        @Override
        public synchronized boolean fits(List<Integer> mine, List<Integer> reservedAhead) {
            int needed = sum(mine);
            refuseIfImpossible(needed);
            refresh();
            return level >= needed + sum(reservedAhead);
        }

        @Override
        public synchronized boolean tryTake(List<Integer> mine, List<Integer> reservedAhead) {
            int needed = sum(mine);
            refuseIfImpossible(needed);
            refresh();
            if (level < needed + sum(reservedAhead)) {
                return false;
            }
            level -= needed;
            takes.incrementAndGet();
            return true;
        }

        @Override
        public void give(List<Integer> amounts) {
            synchronized (this) {
                refresh();
                level = Math.min(cap, level + sum(amounts));
                gives.incrementAndGet();
            }
            capacityChanged();
        }

        /** A meter with no rate never fits by the clock alone: only a give can change its answer. */
        @Override
        public synchronized Long earliestFit(List<Integer> mine, List<Integer> reservedAhead) {
            int total = sum(mine) + sum(reservedAhead);
            if (total > cap || perNano == 0) {
                return null;
            }
            refresh();
            double deficit = total - level;
            if (deficit <= 0) {
                return last;
            }
            return last + (long) Math.ceil(deficit / perNano);
        }

        @Override
        public Replenishment replenishment() {
            return Replenishment.TIME;
        }

        @Override
        public String limiterName() {
            return name;
        }

        @Override
        public String limiterCategory() {
            return "token_bucket";
        }

        @Override
        public long capacity() {
            return cap;
        }

        @Override
        public synchronized long currentInUse() {
            refresh();
            return (long) (cap - level);
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> name + " " + level();
        }
    }

    /**
     * An account whose answer is decided by the test: fit, be short, fit on the pre-check but
     * decline the take (a cap clamped between the two), refuse with an LLM-readable message,
     * throw a bug, or refuse to give back what it took.
     */
    static final class Scripted extends AbstractRateLimiter<Void> {
        enum Mode { FITS, SHORT, TAKE_FAILS, REFUSE, BROKEN }

        private final String name;
        volatile Mode mode = Mode.FITS;
        volatile boolean giveThrows;
        final AtomicInteger takes = new AtomicInteger();
        final AtomicInteger gives = new AtomicInteger();

        Scripted(String name) {
            this.name = name;
        }

        private boolean answer(boolean taking) {
            return switch (mode) {
                case FITS -> true;
                case SHORT -> false;
                case TAKE_FAILS -> !taking;
                case REFUSE -> throw new UncorrectableRuntimeLLMException(name + ": temporarily disabled, pick a different tool");
                case BROKEN -> throw new IllegalStateException(name + " is broken");
            };
        }

        @Override
        public boolean fits(List<Void> mine, List<Void> reservedAhead) {
            return answer(false);
        }

        @Override
        public boolean tryTake(List<Void> mine, List<Void> reservedAhead) {
            boolean taken = answer(true);
            if (taken) {
                takes.incrementAndGet();
            }
            return taken;
        }

        void wakeAll() {
            capacityChanged();
        }

        @Override
        public void give(List<Void> amounts) {
            if (giveThrows) {
                throw new IllegalStateException(name + " cannot give back what it took");
            }
            gives.incrementAndGet();
        }

        @Override
        public Long earliestFit(List<Void> mine, List<Void> reservedAhead) {
            return null;
        }

        @Override
        public String limiterName() {
            return name;
        }

        @Override
        public String limiterCategory() {
            return "semaphore";
        }

        @Override
        public long capacity() {
            return 1;
        }

        @Override
        public long currentInUse() {
            return takes.get() - gives.get();
        }

        @Override
        public void onSuccess() {
        }

        @Override
        public void onRateLimitError(UpstreamFailure failure) {
        }

        @Override
        public RateLimiterStatus getStatus() {
            return () -> name + " " + mode;
        }
    }

    /** Polls a condition, sleeping between reads; false when the deadline passes first. */
    public static boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }
}
