/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.errors.http.*;

import java.util.*;
import java.util.function.*;

/**
 * One admission account: a resource counter with a replenishment kind that a job's
 * {@link Demand} names and that {@link Admission} debits.
 *
 * <p>An account never blocks and never parks anyone. The wait belongs to {@link Admission},
 * which evaluates a job's whole demand as one conjunctive guard and takes every entry in one
 * critical section or none of them. The account answers three questions about amounts and
 * performs two movements:
 *
 * <ul>
 *   <li>{@link #fits}: would {@code mine} fit right now with {@code reservedAhead} set aside.
 *       Pure with respect to admission state (clocked accounts may advance their own
 *       bookkeeping). Refusal is judged on {@code mine} alone: throw an
 *       {@link UncorrectableRuntimeLLMException} when {@code mine} can never fit under the
 *       current configuration or when the account is refusing everyone; when {@code mine}
 *       fits alone but not together with {@code reservedAhead}, the answer is {@code false}.</li>
 *   <li>{@link #tryTake}: the debit. Take {@code mine} if {@code mine} and {@code reservedAhead}
 *       fit together, atomically under the account's own lock, so a cap or circuit change on a
 *       client thread cannot land between the check and the take.</li>
 *   <li>{@link #give}: the credit, at close for {@link Replenishment#RELEASE} accounts, at
 *       rollback and as compensation inside a failed grant for every account.</li>
 *   <li>{@link #earliestFit}: the {@link System#nanoTime()} instant at which {@link #fits}
 *       would become true from the clock alone, or {@code null} when only an external event
 *       can change the answer.</li>
 * </ul>
 *
 * <p>{@code mine} are the asking job's amounts on this account; {@code reservedAhead} are the
 * amounts the head of the admission queue holds reserved on it, empty for the head itself.
 * Each account decides how amounts combine: tokens sum, unit permits count, a router routes
 * each to its bucket, a predicate account ignores them. Amount lists may contain {@code null}
 * for {@code Void}-typed accounts.
 *
 * @param <T> the amount type: {@code Integer} for token counts, {@code Void} for unit permits,
 *            a routing key for multi-bucket limiters
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2025-11-14)
 */
public interface RateLimiter<T> {
    /**
     * How a permit taken from this limiter comes back, which decides whether
     * {@link Admission} may return it when the job finishes.
     */
    enum Replenishment {
        /**
         * The permit is spent against a time window and returns only as time passes.
         * Job completion is irrelevant to it: the upstream budget was consumed the moment
         * the request was issued, and finishing early does not un-issue it. Returning such
         * a permit on completion turns the rate gate into a concurrency cap - throughput
         * becomes {@code permits / job duration} and widening the window (throttling) stops
         * having any effect at all.
         */
        TIME,
        /**
         * The permit represents something held for the duration of the work - a subprocess
         * slot, a connection, heap headroom. It is genuinely free once the job ends, so it
         * must be returned on completion or the capacity leaks.
         */
        RELEASE
    }

    /**
     * Which replenishment model this limiter follows. Deliberately has no default: the
     * answer is a property of the limiter's nature, and guessing it wrong is silent in
     * both directions - a leaked permit one way, a disabled rate limit the other.
     */
    Replenishment replenishment();

    /**
     * Would {@code mine} fit right now with {@code reservedAhead} set aside.
     *
     * @throws UncorrectableRuntimeLLMException when {@code mine} can never fit under the
     *         current configuration, or when the account is refusing everyone
     */
    boolean fits(List<T> mine, List<T> reservedAhead);

    /**
     * Takes {@code mine} if {@code mine} and {@code reservedAhead} fit together; the check and
     * the debit are one atomic step under the account's own lock.
     *
     * @return true when taken, false when the amounts do not fit at this instant
     * @throws UncorrectableRuntimeLLMException under the same conditions as {@link #fits}
     */
    boolean tryTake(List<T> mine, List<T> reservedAhead);

    /**
     * Credits previously taken amounts. For a {@link Replenishment#TIME} account this is a
     * refund of a debit whose request never went upstream.
     */
    void give(List<T> amounts);

    /**
     * The {@link System#nanoTime()} instant at which {@link #fits} would become true for these
     * lists from the clock alone; {@code null} when only an external event can change the
     * answer - always for {@link Replenishment#RELEASE} accounts, and for any account when
     * {@code mine} plus {@code reservedAhead} exceeds its capacity. A past instant means now.
     */
    Long earliestFit(List<T> mine, List<T> reservedAhead);

    /**
     * Installs the single wake this account runs after any self-change that can only
     * increase capacity: a throttle relaxation, a cap raised from headers, a circuit
     * closing, a pushed fleet allotment. The wake is a bare unpark of the admission
     * evaluator and never re-enters an account or the monitor, so it may be run from
     * anywhere, under any lock. {@code null} clears the slot. Decreases never wake anyone.
     */
    void onCapacityChange(Runnable wake);

    /**
     * Installs a lock-free view of how many jobs admission is holding on this account right now:
     * the waiters this account has refused and not yet released, which is this account's own
     * line and nobody else's. An account that drains its line at a rate needs to know whether it
     * has one: with nothing held here a lone arrival passes at once, with a line the releases are
     * spaced, and a line held on some other account is not this account's to pace. Accounts whose
     * answer does not depend on their line ignore this.
     */
    default void observeHeld(IntSupplier held) {
    }

    /**
     * The identity the given amount debits: this limiter for a plain account, the routed
     * bucket for a router. Head reservation, waiter counts and events all key on it.
     */
    LimiterIdentity accountFor(T amount);

    /**
     * Notifies the rate limiter of a successful operation.
     * Used by adaptive strategies to relax throttling.
     */
    void onSuccess();

    /**
     * Notifies the rate limiter of a successful operation, scoped to the
     * specific input that was acquired. Default implementation delegates to
     * {@link #onSuccess()} for limiters that don't care about per-input
     * bucketing. Multi-bucket limiters (e.g. one elastic window per upstream
     * sub-service) override this to signal the correct bucket.
     *
     * @param input The same value that was passed as an amount in the demand
     */
    default void onSuccess(T input) {
        onSuccess();
    }

    /**
     * Notifies the rate limiter that an upstream request failed. Used by adaptive strategies
     * to tighten throttling, and - just as importantly - to record what the upstream actually
     * said, so anything the limiter subsequently emits can name the cause.
     *
     * @param failure the upstream's status and response detail; never null
     */
    void onRateLimitError(UpstreamFailure failure);

    /**
     * Per-input variant of {@link #onRateLimitError(UpstreamFailure)}. Default implementation
     * delegates for limiters that don't care about per-input bucketing. Multi-bucket limiters
     * override this to tighten only the bucket that failed.
     *
     * @param failure the upstream's status and response detail; never null
     * @param input The same value that was passed as an amount in the demand
     */
    default void onRateLimitError(UpstreamFailure failure, T input) {
        onRateLimitError(failure);
    }

    /**
     * Whether this rate limiter's operations require HTTP connections. A demand that names
     * such a limiter also names the shared HTTP connection gate.
     *
     * @return true if operations require HTTP, false otherwise
     */
    default boolean requiresHttpConnection() {
        return false;
    }
}
