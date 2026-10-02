/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.admission.*;
import org.slf4j.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Console observer that prints a "state of the system" snapshot on a
 * sampled cadence. One block the reader can glance at to see what's
 * happening across all limiters, running jobs, active workflows, and the
 * dispatcher queues.
 *
 * <h2>Output</h2>
 * A single snapshot block every {@link #SAMPLE_EVERY} incoming events, with
 * five sections:
 * <ul>
 *   <li><b>Limiters</b> sorted by utilisation descending, one row per limiter
 *       an event has named. Each row carries the name (cut to {@link #NAME_WIDTH}
 *       with an ellipsis), a gradient bar, percent, waiter count, humanised
 *       used/total counts (1000-based: {@code 1.8M}, {@code 7.9G}),
 *       per-category unit ({@code B}, {@code tok}, {@code req}, {@code slot}),
 *       and a rightmost <i>status</i> column populated only when the event's
 *       {@link LimiterEvent#statusIndicator()} reports an abnormal state
 *       ({@code blocked}, {@code probing}, {@code throttle:2.0x},
 *       {@code pace:1500ms}). Abnormal rows get tinted (red for blocked,
 *       yellow for throttle/pace/probing) while the gradient bar keeps its
 *       own saturation colors. The <i>used</i> figure is per-category: for
 *       {@code token_bucket} it is the trailing-minute throughput (sum of
 *       {@code amount} on {@code GRANTED_*} events) so it is comparable to
 *       the per-minute capacity; every other category displays the limiter's
 *       instantaneous {@link LimiterEvent#inUse()}. A CPU row follows when the
 *       JVM exposes process load.</li>
 *   <li><b>Memory</b> one line from the memory gate's own status summary,
 *       every snapshot, whether or not the gate has emitted an event.</li>
 *   <li><b>Jobs running</b> prominent total plus a breakdown by
 *       {@link JobType}, each row tinted in the type's color. A job counts
 *       once from its first {@link JobStartedEvent} to its {@link TerminalEvent},
 *       whatever retries republish in between.</li>
 *   <li><b>Workflows active</b> count of workflows with at least one running
 *       job.</li>
 *   <li><b>Queue</b> standard + fast dispatcher queue sizes and the jobs parked
 *       in admission. Under healthy operation the two queues are zero; non-zero
 *       is a pure alarm indicator.</li>
 * </ul>
 *
 * <p>Subscribes broadly to {@link JobEvent} and filters for
 * {@link LimiterEvent} and {@link LifecycleEvent} in a predicate so one
 * subscription covers both signals (rate limiters for the limiter section,
 * lifecycle events for the running-jobs count).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-14)
 */
public class SystemHealthReporter implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(SystemHealthReporter.class);

    /**
     * Print a full snapshot once every {@code SAMPLE_EVERY} events.
     * Adjust based on observed traffic: too low floods the log, too high
     * and the reader misses transient pressure.
     */
    private static final int SAMPLE_EVERY = 50;

    private static final int NAME_WIDTH = 28;
    private static final int BAR_WIDTH = 20;
    private static final double GRADIENT_START = 0.6;
    private static final String RESET = "\u001b[0m";
    private static final String BOLD = "\u001b[1m";
    // Row-level tint for limiter rows in an abnormal state. Red for blocked,
    // yellow/orange for any throttle, pace or probing signal. Applied to the
    // whole line so the reader spots the anomaly without scanning columns.
    private static final String ROW_BLOCKED = "\u001b[38;2;255;80;80m";
    private static final String ROW_THROTTLED = "\u001b[38;2;230;170;50m";

    /** Latest known event per limiter name. Drives the limiter section of the snapshot. */
    private final Map<String, LimiterEvent> latestByLimiter = new ConcurrentHashMap<>();

    /**
     * Rolling sum of {@code amount} for {@code GRANTED_*} events per limiter
     * over the trailing minute. For {@code token_bucket} limiters the gauge
     * reads this instead of {@link LimiterEvent#inUse()}: the LLM token
     * bucket's {@code inUse} is the bucket-depletion snapshot ({@code
     * capacity - availableTokens}), which sits at the bucket floor under
     * sustained load and conceals the real throughput. Summing
     * {@code amount} on grant events answers the operator's actual question:
     * "how many tokens per minute am I getting through this limiter right
     * now?" The number is directly comparable to {@code capacity}, which is
     * already a per-minute figure for token buckets.
     */
    private final Map<String, RollingMinute> throughputByLimiter = new ConcurrentHashMap<>();

    /**
     * Active jobs keyed by jobId. The dispatcher republishes {@link JobStartedEvent}
     * once per attempt (rate-limit retries, transient-error retries, output-truncation
     * retries), but only one {@link TerminalEvent} ever fires per job. Tracking by
     * jobId with idempotent put/remove prevents the running count from leaking on
     * each retry. Per-type and per-workflow counts are derived from the values at
     * snapshot time.
     */
    private final Map<String, JobMeta> activeJobs = new ConcurrentHashMap<>();

    /** Per-job identity needed to render the snapshot: type for the breakdown, workflowId for the workflow count. */
    private record JobMeta(JobType type, String workflowId) {}

    /** Global event counter; every {@link #SAMPLE_EVERY}th tick triggers a snapshot. */
    private final AtomicLong eventCounter = new AtomicLong();

    @Override
    public Predicate<JobEvent> getPredicate() {
        return event -> event instanceof LimiterEvent || event instanceof LifecycleEvent;
    }

    @Override
    public void observe(JobEvent event) {
        if (event instanceof LimiterEvent le) {
            handleLimiter(le);
        } else if (event instanceof JobStartedEvent started) {
            incrementRunning(started);
        } else if (event instanceof TerminalEvent terminal) {
            decrementRunning(terminal);
        }
        // Periodic full-system snapshot - driven by any matching event
        if (eventCounter.incrementAndGet() % SAMPLE_EVERY == 0) {
            logSnapshot();
        }
    }

    private void handleLimiter(LimiterEvent event) {
        latestByLimiter.put(event.limiterName(), event);
        LimiterEvent.Type type = event.type();
        if (type == LimiterEvent.Type.GRANTED_IMMEDIATE || type == LimiterEvent.Type.GRANTED_FROM_HOLD) {
            throughputByLimiter
                    .computeIfAbsent(event.limiterName(), k -> new RollingMinute())
                    .add(event.amount());
        }
    }

    private void incrementRunning(JobStartedEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot == null) return;
        String jobId = snapshot.getJobId();
        if (jobId == null) return;
        // putIfAbsent: retry attempts republish JobStartedEvent with the same jobId;
        // only the first start counts, so the running total reflects unique in-flight jobs.
        activeJobs.putIfAbsent(jobId, new JobMeta(jobTypeOf(event), snapshot.getWorkflowId()));
    }

    private void decrementRunning(TerminalEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot == null) return;
        String jobId = snapshot.getJobId();
        if (jobId == null) return;
        activeJobs.remove(jobId);
    }

    private static JobType jobTypeOf(JobEvent event) {
        JobSnapshot snapshot = event.snapshot();
        if (snapshot == null) return JobType.JOB;
        JobType type = snapshot.getJobType();
        return type != null ? type : JobType.JOB;
    }

    /**
     * Prints the full system snapshot block: limiter rows sorted by
     * utilisation, running jobs by type, dispatcher queue depths.
     */
    private void logSnapshot() {
        StringBuilder sb = new StringBuilder("[System health snapshot]\n");
        appendLimiterSection(sb);
        appendMemorySection(sb);
        appendJobSection(sb);
        appendWorkflowSection(sb);
        appendQueueSection(sb);
        // Trim the trailing newline for a cleaner log entry.
        if (sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        log.info(sb.toString());
    }

    /**
     * The memory gate's own account of itself, every snapshot, whether or not it has emitted an
     * event. Its row above shows only what every limiter shows; this line carries the zone, how
     * many jobs admission is holding on memory, and how long until the next release is due if no
     * job finishes first.
     */
    private void appendMemorySection(StringBuilder sb) {
        sb.append("  Memory: ").append(MemoryPressureGate.getInstance().getStatus().summary()).append('\n');
    }

    private void appendWorkflowSection(StringBuilder sb) {
        long workflows = activeJobs.values().stream()
                .map(JobMeta::workflowId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        sb.append(String.format("  Workflows active: %d%n", workflows));
    }

    private void appendLimiterSection(StringBuilder sb) {
        List<LimiterEvent> limiters = new ArrayList<>(latestByLimiter.values());
        if (limiters.isEmpty()) {
            sb.append("  Limiters: (none active)\n");
            return;
        }
        limiters.sort(Comparator.comparingDouble(this::utilization).reversed());
        sb.append("  Limiters:\n");
        for (LimiterEvent ev : limiters) {
            double ratio = utilization(ev);
            String bar = formatBar(ratio);
            String status = ev.statusIndicator();
            String rowTint = tintForStatus(status);
            // Split the row into prefix (name + pct), the pre-colored bar, and
            // suffix (waiters + used/total + unit + status). This lets the
            // row tint paint the text columns without stomping on the bar's
            // own gradient, which still carries useful saturation signal
            // even when the limiter is throttled.
            String prefix = String.format("    %-" + NAME_WIDTH + "s %6.1f%% ",
                    truncateName(ev.limiterName()),
                    ratio * 100.0);
            String suffix = String.format("  waiters=%-3d  used: %6s  total: %-6s %-4s  %s",
                    ev.waiters(),
                    humanize(effectiveInUse(ev)),
                    humanize(ev.capacity()),
                    unitFor(ev.limiterCategory()),
                    status != null ? status : "");
            // Trim trailing spaces off the suffix so the ANSI reset doesn't
            // paint a sea of colored blanks past the last visible character.
            int end = suffix.length();
            while (end > 0 && suffix.charAt(end - 1) == ' ') end--;
            String suffixTrimmed = suffix.substring(0, end);
            if (rowTint != null) {
                // prefix in tint, bar unchanged (its own ANSI codes are live
                // in the middle), suffix re-tinted after the bar's trailing
                // RESET clears the color.
                sb.append(rowTint).append(prefix)
                  .append(bar)
                  .append(rowTint).append(suffixTrimmed).append(RESET).append('\n');
            } else {
                sb.append(prefix).append(bar).append(suffixTrimmed).append('\n');
            }
        }
        appendCpuRow(sb);
    }

    private void appendCpuRow(StringBuilder sb) {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean ext) {
                double process = ext.getProcessCpuLoad();
                double system = ext.getCpuLoad();
                int cores = ext.getAvailableProcessors();
                if (process < 0 || system < 0) return;
                String bar = formatBar(process);
                String prefix = String.format("    %-" + NAME_WIDTH + "s %6.1f%% ",
                        "cpu (" + cores + " cores)", process * 100.0);
                String suffix = String.format("  system: %.0f%%", system * 100.0);
                sb.append(prefix).append(bar).append(suffix).append('\n');
            }
        }
        catch (Throwable ignored) {
        }
    }

    private static String tintForStatus(String status) {
        if (status == null) return null;
        if (status.contains("blocked")) return ROW_BLOCKED;
        if (status.contains("throttle") || status.contains("pace") || status.contains("probing")) return ROW_THROTTLED;
        return null;
    }

    private static String truncateName(String name) {
        if (name.length() <= NAME_WIDTH) return name;
        return name.substring(0, NAME_WIDTH - 3) + "...";
    }

    /**
     * Per-category unit label shown at the end of each limiter row so the
     * reader knows what {@code 5.3G} or {@code 273.1K} measures.
     */
    private static String unitFor(String category) {
        if (category == null) return "";
        return switch (category) {
            case "memory" -> "B";
            case "token_bucket" -> "tok";
            case "elastic_window" -> "req";
            case "semaphore" -> "slot";
            default -> "";
        };
    }

    private void appendJobSection(StringBuilder sb) {
        EnumMap<JobType, Integer> counts = new EnumMap<>(JobType.class);
        for (JobMeta meta : activeJobs.values()) {
            counts.merge(meta.type(), 1, Integer::sum);
        }
        List<Map.Entry<JobType, Integer>> breakdown = new ArrayList<>();
        int total = 0;
        for (JobType type : JobType.values()) {
            int count = counts.getOrDefault(type, 0);
            if (count > 0) {
                breakdown.add(Map.entry(type, count));
                total += count;
            }
        }
        // Prominent total - bold + bright white so it's the first thing the
        // eye lands on when the snapshot prints.
        sb.append(String.format("  %sJobs running: %d total%s%n", BOLD + "\u001b[97m", total, RESET));
        if (total == 0) {
            return;
        }
        // Sort descending by count so the dominant types are at the top.
        breakdown.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<JobType, Integer> entry : breakdown) {
            double ratio = (double) entry.getValue() / total;
            String color = colorFor(entry.getKey());
            // Tint the entire row (name + count + bar) in the type's color so
            // each job type is visually associated with its line, not just
            // its bar fill.
            sb.append(String.format("    %s%-10s %4d%s  %s%n",
                    color,
                    entry.getKey().name(),
                    entry.getValue(),
                    RESET,
                    formatTypeBar(ratio, color)));
        }
    }

    /**
     * Renders a single-color bar for the job-type section. The filled portion
     * is drawn in the type's color; the empty remainder is dim gray. Every
     * row uses the same max (total running jobs), so row lengths are
     * directly comparable at a glance.
     */
    private String formatTypeBar(double ratio, String color) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * BAR_WIDTH);
        StringBuilder sb = new StringBuilder();
        if (filled > 0) {
            sb.append(color).append("█".repeat(filled));
        }
        int empty = BAR_WIDTH - filled;
        if (empty > 0) {
            sb.append("\u001b[90m").append("░".repeat(empty));
        }
        sb.append(RESET);
        return sb.toString();
    }

    /**
     * ANSI 24-bit foreground color per job type. Colors are chosen to be
     * visually distinct and readable on both dark and light terminals.
     */
    private static String colorFor(JobType type) {
        return switch (type) {
            case THINKER -> "\u001b[38;2;100;150;255m";   // blue
            case DOER -> "\u001b[38;2;60;200;60m";         // green
            case TOOL -> "\u001b[38;2;80;200;220m";        // cyan
            case LLM_CALL -> "\u001b[38;2;220;100;220m";   // magenta
            case GUARDRAIL -> "\u001b[38;2;220;200;40m";   // yellow
            case JOB -> "\u001b[38;2;160;160;160m";        // gray
            case UTILITY -> "\u001b[38;2;220;220;220m";    // light gray
        };
    }

    private void appendQueueSection(StringBuilder sb) {
        Map<String, Object> stats = JobDispatcher.INSTANCE.getStatistics();
        Object standard = stats.get("standardQueueSize");
        Object fast = stats.get("fastQueueSize");
        Object admission = stats.get("admissionQueueSize");
        sb.append(String.format("  Queue: %s standard, %s fast, %s parked in admission%n", standard, fast, admission));
    }

    private double utilization(LimiterEvent event) {
        return event.capacity() > 0 ? (double) effectiveInUse(event) / event.capacity() : 0.0;
    }

    /**
     * In-use figure to display and to drive the percent bar. For
     * {@code token_bucket} limiters this is the trailing-minute throughput
     * (tokens granted in the last 60s), which is what the operator actually
     * needs to see: the LLM bucket's raw {@code inUse} is the depletion
     * snapshot, which sits at the bucket floor under sustained load and
     * makes a 90%-utilised limiter look idle. For every other category,
     * {@link LimiterEvent#inUse()} already represents instantaneous
     * occupancy (concurrent slots, retained bytes), so it stays.
     */
    private long effectiveInUse(LimiterEvent event) {
        if ("token_bucket".equals(event.limiterCategory())) {
            RollingMinute window = throughputByLimiter.get(event.limiterName());
            return window != null ? window.sumLastMinute() : 0;
        }
        return event.inUse();
    }

    /**
     * Append-then-evict deque of grant amounts, summed over a sliding minute.
     * One instance per limiter. Writes are O(1); reads evict expired entries
     * from the head, then sum what remains. The deque is bounded in steady
     * state by grant rate per minute, which is small enough that the linear
     * sum is not worth replacing with a ring buffer.
     */
    private static final class RollingMinute {
        private record Entry(long timeMs, long amount) {}
        private static final long WINDOW_MS = 60_000;
        private final ConcurrentLinkedDeque<Entry> entries = new ConcurrentLinkedDeque<>();

        void add(long amount) {
            entries.add(new Entry(System.currentTimeMillis(), amount));
        }

        long sumLastMinute() {
            long cutoff = System.currentTimeMillis() - WINDOW_MS;
            Entry head;
            while ((head = entries.peek()) != null && head.timeMs() < cutoff) {
                entries.poll();
            }
            long sum = 0;
            for (Entry e : entries) {
                sum += e.amount();
            }
            return sum;
        }
    }

    /**
     * Renders a count in short SI-style form: {@code 1.8M}, {@code 151.3K},
     * {@code 7.9G}. Values below 1000 are printed as-is. Uses 1000-based
     * units (not 1024-based) so token counts like 2000000 show as
     * {@code 2.0M} rather than {@code 1.9M}.
     */
    private static String humanize(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1_000.0);
        if (n < 1_000_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n < 1_000_000_000_000L) return String.format("%.1fG", n / 1_000_000_000.0);
        return String.format("%.1fT", n / 1_000_000_000_000.0);
    }

    private String formatBar(double ratio) {
        int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * BAR_WIDTH);
        int gradStart = (int) (GRADIENT_START * BAR_WIDTH);
        StringBuilder sb = new StringBuilder();
        int solidGreen = Math.min(filled, gradStart);
        if (solidGreen > 0) {
            sb.append(gradientColor(0)).append("█".repeat(solidGreen));
        }
        for (int i = gradStart; i < filled; i++) {
            sb.append(gradientColor((double) i / BAR_WIDTH)).append('█');
        }
        int empty = BAR_WIDTH - filled;
        if (empty > 0) {
            sb.append("\u001b[90m").append("░".repeat(empty));
        }
        sb.append(RESET);
        return sb.toString();
    }

    private String gradientColor(double position) {
        if (position <= GRADIENT_START) return "\u001b[38;2;0;200;0m";
        double t = (position - GRADIENT_START) / (1.0 - GRADIENT_START);
        int r, g;
        if (t <= 0.5) {
            r = (int) (220 * t * 2);
            g = 200;
        } else {
            r = 220;
            g = (int) (200 * (1 - (t - 0.5) * 2));
        }
        return String.format("\u001b[38;2;%d;%d;0m", r, g);
    }
}
