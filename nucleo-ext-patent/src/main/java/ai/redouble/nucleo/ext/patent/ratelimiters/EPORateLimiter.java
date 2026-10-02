/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.ext.patent.ratelimiters;

import ai.redouble.nucleo.ext.patent.epo.*;
import ai.redouble.nucleo.harness.admission.*;
import ai.redouble.nucleo.harness.errors.http.*;

import java.util.*;

/**
 * Multi-bucket rate limiter for EPO Open Patent Services.
 *
 * <p>EPO reports per-service quotas in the {@code X-Throttling-Control} response
 * header (search, retrieval, inpadoc, images, other). Each bucket is rate-limited
 * independently by EPO, so we hold one {@link ElasticWindowRateLimiter} per bucket
 * and route every admission question, pressure and recovery signal by {@link EPOService}.
 * The win over a shared single limiter: when only {@code search} is black-flagged,
 * biblio / family / images calls keep flowing at full speed instead of collapsing
 * to 1/10th throughput alongside search.
 *
 * <p>The router has no identity of its own: {@link #accountFor} names the routed bucket, so
 * head reservation, waiter counts, events and health rows are all per service bucket.
 *
 * <p>Each bucket is a private inner subclass of {@link ElasticWindowRateLimiter}
 * that hardcodes its service-specific QPM limit. Inner
 * classes are used because the parent's constructor calls the template methods
 * {@code getBaseWindowMs} / {@code getMaxRequests} BEFORE any subclass instance
 * fields are initialized - values must come from a static source.
 *
 * <p>The no-arg {@link #onSuccess()} and {@link #onRateLimitError(UpstreamFailure)} fallbacks
 * are no-ops because the dispatcher always has the {@link EPOService} input stored
 * in {@code JobRequirements} and takes the typed path. They only fire if someone
 * calls the untyped signature directly.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class EPORateLimiter implements RateLimiter<EPOService> {
    private final Map<EPOService, ElasticWindowRateLimiter> buckets;

    EPORateLimiter() {
        this.buckets = new EnumMap<>(EPOService.class);
        buckets.put(EPOService.SEARCH, new SearchBucket());
        buckets.put(EPOService.RETRIEVAL, new RetrievalBucket());
        buckets.put(EPOService.INPADOC, new InpadocBucket());
        buckets.put(EPOService.IMAGES, new ImagesBucket());
        buckets.put(EPOService.OTHER, new OtherBucket());
    }

    @Override
    public Replenishment replenishment() {
        return Replenishment.TIME;  // every bucket is an ElasticWindowRateLimiter
    }

    @Override
    public boolean fits(List<EPOService> mine, List<EPOService> reservedAhead) {
        Map<EPOService, Integer> reserved = countBy(reservedAhead);
        for (Map.Entry<EPOService, Integer> need : countBy(mine).entrySet()) {
            if (!buckets.get(need.getKey()).fits(units(need.getValue()), units(reserved.getOrDefault(need.getKey(), 0)))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean tryTake(List<EPOService> mine, List<EPOService> reservedAhead) {
        Map<EPOService, Integer> reserved = countBy(reservedAhead);
        List<Map.Entry<EPOService, Integer>> taken = new ArrayList<>();
        for (Map.Entry<EPOService, Integer> need : countBy(mine).entrySet()) {
            if (!buckets.get(need.getKey()).tryTake(units(need.getValue()), units(reserved.getOrDefault(need.getKey(), 0)))) {
                for (Map.Entry<EPOService, Integer> undo : taken) {
                    buckets.get(undo.getKey()).give(units(undo.getValue()));
                }
                return false;
            }
            taken.add(need);
        }
        return true;
    }

    @Override
    public void give(List<EPOService> amounts) {
        for (Map.Entry<EPOService, Integer> returned : countBy(amounts).entrySet()) {
            buckets.get(returned.getKey()).give(units(returned.getValue()));
        }
    }

    @Override
    public Long earliestFit(List<EPOService> mine, List<EPOService> reservedAhead) {
        Map<EPOService, Integer> reserved = countBy(reservedAhead);
        Long latest = null;
        for (Map.Entry<EPOService, Integer> need : countBy(mine).entrySet()) {
            Long at = buckets.get(need.getKey()).earliestFit(units(need.getValue()), units(reserved.getOrDefault(need.getKey(), 0)));
            if (at == null) {
                return null;
            }
            if (latest == null || at > latest) {
                latest = at;
            }
        }
        return latest;
    }

    @Override
    public void onCapacityChange(Runnable wake) {
        for (ElasticWindowRateLimiter bucket : buckets.values()) {
            bucket.onCapacityChange(wake);
        }
    }

    @Override
    public LimiterIdentity accountFor(EPOService service) {
        return buckets.get(service);
    }

    private static Map<EPOService, Integer> countBy(List<EPOService> amounts) {
        Map<EPOService, Integer> counts = new EnumMap<>(EPOService.class);
        for (EPOService service : amounts) {
            counts.merge(service, 1, Integer::sum);
        }
        return counts;
    }

    private static List<Void> units(int count) {
        return Collections.nCopies(count, null);
    }

    @Override
    public void onSuccess() {
        // No service context - per-service signaling via onSuccess(EPOService) is the only path.
    }

    @Override
    public void onSuccess(EPOService service) {
        buckets.get(service).onSuccess();
    }

    @Override
    public void onRateLimitError(UpstreamFailure failure) {
        // No service context - per-service signaling via onRateLimitError(failure, EPOService) is the only path.
    }

    @Override
    public void onRateLimitError(UpstreamFailure failure, EPOService service) {
        buckets.get(service).onRateLimitError(failure);
    }

    /**
     * Bumps advisory pressure on a single service bucket without affecting
     * circuit state. Called from {@code EPOClient.postProcessResponse} when
     * EPO reports a non-green status for that bucket in the
     * {@code X-Throttling-Control} header.
     */
    public void onAdvisoryPressure(EPOService service) {
        buckets.get(service).onAdvisoryPressure();
    }

    /**
     * Reports a bucket-specific success (green advisory). Helps buckets
     * recover from throttled state when their individual pressure clears.
     */
    public void onAdvisorySuccess(EPOService service) {
        buckets.get(service).onSuccess();
    }

    @Override
    public boolean requiresHttpConnection() {
        return true;
    }

    // ==================== Per-service buckets ====================
    // Each bucket is an independent ElasticWindowRateLimiter with hardcoded
    // 60-second base window and a service-specific QPM ceiling.

    private static final class SearchBucket extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() { return 60_000; }
        @Override protected int getMaxRequests() { return 6; }
        @Override public String limiterName() { return "epo:search"; }

        // EPO search is the most aggressive robot-detection bucket.
        // Hit harder on 429/robot and cool down longer before retrying.
        @Override protected double getThrottleIncrement() { return 2.0; }
        @Override protected long getInitialCooldownMs() { return 60_000; }
        @Override protected long getMaxCooldownMs() { return 600_000; }
    }

    private static final class RetrievalBucket extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() { return 60_000; }
        @Override protected int getMaxRequests() { return 30; }
        @Override public String limiterName() { return "epo:retrieval"; }
    }

    private static final class InpadocBucket extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() { return 60_000; }
        @Override protected int getMaxRequests() { return 20; }
        @Override public String limiterName() { return "epo:inpadoc"; }
    }

    private static final class ImagesBucket extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() { return 60_000; }
        @Override protected int getMaxRequests() { return 30; }
        @Override public String limiterName() { return "epo:images"; }
    }

    private static final class OtherBucket extends ElasticWindowRateLimiter {
        @Override protected long getBaseWindowMs() { return 60_000; }
        @Override protected int getMaxRequests() { return 60; }
        @Override public String limiterName() { return "epo:other"; }
    }
}
