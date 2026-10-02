/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.tools.benchmark;

import ai.redouble.nucleo.harness.models.*;
import java.text.*;
import java.util.*;

/**
 * What a benchmark measured: the job class and the grade it raced, the reference row, one
 * row per model and run, and the means per model. Rendered as a table for the log by
 * {@link #table()}; a host persists the object where it keeps such things.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-17)
 */
public class BenchmarkReport {
    /** One run of the job on one model. */
    public static class Row {
        private String modelId;
        private String identity;
        private String provider;
        private String servedModelId;
        private boolean reference;
        private int run;
        private boolean succeeded;
        private String failure;
        private long calls;
        private long iterations;
        private long inputTokens;
        private long outputTokens;
        private long latencyMs;
        private long wallMs;
        private Double cost;
        private String currency;
        private Double judgeScore;
        private Double judgeAccuracy;
        private Double judgeReasoning;
        private String judgeToolFault;
        private String judgeReason;
        private Double score;

        public String getModelId() {return modelId;}

        public void setModelId(String modelId) {this.modelId = modelId;}

        public String getIdentity() {return identity;}

        public void setIdentity(String identity) {this.identity = identity;}

        public String getProvider() {return provider;}

        public void setProvider(String provider) {this.provider = provider;}

        public String getServedModelId() {return servedModelId;}

        public void setServedModelId(String servedModelId) {this.servedModelId = servedModelId;}

        public boolean isReference() {return reference;}

        public void setReference(boolean reference) {this.reference = reference;}

        public int getRun() {return run;}

        public void setRun(int run) {this.run = run;}

        public boolean isSucceeded() {return succeeded;}

        public void setSucceeded(boolean succeeded) {this.succeeded = succeeded;}

        public String getFailure() {return failure;}

        public void setFailure(String failure) {this.failure = failure;}

        public long getCalls() {return calls;}

        public void setCalls(long calls) {this.calls = calls;}

        public long getIterations() {return iterations;}

        public void setIterations(long iterations) {this.iterations = iterations;}

        public long getInputTokens() {return inputTokens;}

        public void setInputTokens(long inputTokens) {this.inputTokens = inputTokens;}

        public long getOutputTokens() {return outputTokens;}

        public void setOutputTokens(long outputTokens) {this.outputTokens = outputTokens;}

        public long getLatencyMs() {return latencyMs;}

        public void setLatencyMs(long latencyMs) {this.latencyMs = latencyMs;}

        public long getWallMs() {return wallMs;}

        public void setWallMs(long wallMs) {this.wallMs = wallMs;}

        public Double getCost() {return cost;}

        public void setCost(Double cost) {this.cost = cost;}

        public String getCurrency() {return currency;}

        public void setCurrency(String currency) {this.currency = currency;}

        /** Accuracy plus reasoning, out of 10; null when the run was not judged. */
        public Double getJudgeScore() {return judgeScore;}

        public void setJudgeScore(Double judgeScore) {this.judgeScore = judgeScore;}

        /** Out of 7. */
        public Double getJudgeAccuracy() {return judgeAccuracy;}

        public void setJudgeAccuracy(Double judgeAccuracy) {this.judgeAccuracy = judgeAccuracy;}

        /** Out of 3. */
        public Double getJudgeReasoning() {return judgeReasoning;}

        public void setJudgeReasoning(Double judgeReasoning) {this.judgeReasoning = judgeReasoning;}

        /** The tool the run called wrongly, skipped or misread, as the judge named it; null when none. */
        public String getJudgeToolFault() {return judgeToolFault;}

        public void setJudgeToolFault(String judgeToolFault) {this.judgeToolFault = judgeToolFault;}

        public String getJudgeReason() {return judgeReason;}

        public void setJudgeReason(String judgeReason) {this.judgeReason = judgeReason;}

        public Double getScore() {return score;}

        public void setScore(Double score) {this.score = score;}
    }

    /** One model's means across its runs. */
    public static class Summary {
        private String modelId;
        private boolean reference;
        private int runs;
        private int failed;
        private Double meanCost;
        private String currency;
        private Double meanWallMs;
        private Double meanLatencyPerCallMs;
        private Double meanCalls;
        private Double meanIterations;
        private Double meanJudgeScore;
        private Double meanScore;

        public String getModelId() {return modelId;}

        public void setModelId(String modelId) {this.modelId = modelId;}

        public boolean isReference() {return reference;}

        public void setReference(boolean reference) {this.reference = reference;}

        public int getRuns() {return runs;}

        public void setRuns(int runs) {this.runs = runs;}

        public int getFailed() {return failed;}

        public void setFailed(int failed) {this.failed = failed;}

        public Double getMeanCost() {return meanCost;}

        public void setMeanCost(Double meanCost) {this.meanCost = meanCost;}

        public String getCurrency() {return currency;}

        public void setCurrency(String currency) {this.currency = currency;}

        public Double getMeanWallMs() {return meanWallMs;}

        public void setMeanWallMs(Double meanWallMs) {this.meanWallMs = meanWallMs;}

        public Double getMeanLatencyPerCallMs() {return meanLatencyPerCallMs;}

        public void setMeanLatencyPerCallMs(Double meanLatencyPerCallMs) {this.meanLatencyPerCallMs = meanLatencyPerCallMs;}

        public Double getMeanCalls() {return meanCalls;}

        public void setMeanCalls(Double meanCalls) {this.meanCalls = meanCalls;}

        public Double getMeanIterations() {return meanIterations;}

        public void setMeanIterations(Double meanIterations) {this.meanIterations = meanIterations;}

        public Double getMeanJudgeScore() {return meanJudgeScore;}

        public void setMeanJudgeScore(Double meanJudgeScore) {this.meanJudgeScore = meanJudgeScore;}

        public Double getMeanScore() {return meanScore;}

        public void setMeanScore(Double meanScore) {this.meanScore = meanScore;}
    }

    private String jobClass;
    private Grade grade;
    private int runs;
    private String judgeModel;
    private String judgeFailure;
    private long elapsedMs;
    private List<Row> rows = new ArrayList<>();
    private List<Summary> summaries = new ArrayList<>();

    public String getJobClass() {return jobClass;}

    public void setJobClass(String jobClass) {this.jobClass = jobClass;}

    public Grade getGrade() {return grade;}

    public void setGrade(Grade grade) {this.grade = grade;}

    public int getRuns() {return runs;}

    public void setRuns(int runs) {this.runs = runs;}

    public String getJudgeModel() {return judgeModel;}

    public void setJudgeModel(String judgeModel) {this.judgeModel = judgeModel;}

    public String getJudgeFailure() {return judgeFailure;}

    public void setJudgeFailure(String judgeFailure) {this.judgeFailure = judgeFailure;}

    public long getElapsedMs() {return elapsedMs;}

    public void setElapsedMs(long elapsedMs) {this.elapsedMs = elapsedMs;}

    public List<Row> getRows() {return rows;}

    public void setRows(List<Row> rows) {this.rows = rows;}

    public List<Summary> getSummaries() {return summaries;}

    public void setSummaries(List<Summary> summaries) {this.summaries = summaries;}

    private static final String RESET = "[0m";
    private static final String GREEN = "[38;5;46m";
    private static final String ORANGE = "[38;5;208m";
    private static final String RED = "[38;5;196m";
    /** Green through yellow and orange to red: index 0 is best, the last is worst. */
    private static final int[] HEAT = {46, 82, 118, 154, 190, 226, 220, 214, 208, 202, 196};
    /** Failures up to this share of a model's runs colour orange; more colour red. */
    private static final double FAILED_ORANGE_UP_TO = 0.25;

    /**
     * The means per model as a fixed-width table, the reference first, for a log line.
     * Coloured for a terminal: failures green at zero, orange up to a quarter of the runs,
     * red past that; cost per run heat-mapped from the cheapest model (green) to the
     * dearest (red); the judge's score and the scorer's from their best (green) to their
     * worst (red) on their own scales.
     */
    public String table() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Benchmark of %s at grade %s, %d run(s) per model%s%n", jobClass, grade, runs,
                judgeModel != null ? ", judged by " + judgeModel : ""));
        sb.append(String.format("  %-34s %4s %6s    %12s %10s %9s %6s %6s %6s %6s%n",
                "model", "runs", "failed", "cost/run", "wall ms", "ms/call", "calls", "iters", "judge", "score"));
        double cheapest = Double.MAX_VALUE;
        double dearest = -Double.MAX_VALUE;
        for (Summary s : summaries) {
            if (s.getMeanCost() != null) {
                cheapest = Math.min(cheapest, s.getMeanCost());
                dearest = Math.max(dearest, s.getMeanCost());
            }
        }
        for (Summary s : summaries) {
            String failedColor = s.getFailed() == 0 ? GREEN : s.getFailed() <= s.getRuns() * FAILED_ORANGE_UP_TO ? ORANGE : RED;
            String costColor = s.getMeanCost() == null ? "" : heat(dearest > cheapest ? (s.getMeanCost() - cheapest) / (dearest - cheapest) : 0);
            String judgeColor = s.getMeanJudgeScore() == null ? "" : heat(1 - s.getMeanJudgeScore() / 10);
            String scoreColor = s.getMeanScore() == null ? "" : heat(1 - s.getMeanScore());
            sb.append("  ").append(String.format("%-34s %4d ", (s.isReference() ? "* " : "") + s.getModelId(), s.getRuns()))
                    .append(cell(String.valueOf(s.getFailed()), 6, failedColor)).append("    ")
                    .append(cell(s.getMeanCost() == null ? "-" : money(s.getMeanCost(), s.getCurrency()), 12, costColor)).append(' ')
                    .append(cell(s.getMeanWallMs() == null ? "-" : millis(s.getMeanWallMs()), 10, "")).append(' ')
                    .append(cell(s.getMeanLatencyPerCallMs() == null ? "-" : millis(s.getMeanLatencyPerCallMs()), 9, "")).append(' ')
                    .append(cell(s.getMeanCalls() == null ? "-" : count(s.getMeanCalls()), 6, "")).append(' ')
                    .append(cell(s.getMeanIterations() == null ? "-" : count(s.getMeanIterations()), 6, "")).append(' ')
                    .append(cell(s.getMeanJudgeScore() == null ? "-" : count(s.getMeanJudgeScore()), 6, judgeColor)).append(' ')
                    .append(cell(s.getMeanScore() == null ? "-" : String.format("%.2f", s.getMeanScore()), 6, scoreColor)).append('\n');
        }
        if (judgeFailure != null) {
            sb.append("  judge: ").append(judgeFailure).append('\n');
        }
        sb.append("  * the reference: the deployment's own choice for the grade");
        return sb.toString();
    }

    /** A right-aligned cell padded before it is coloured, so the escape codes never shift the columns. */
    private static String cell(String text, int width, String color) {
        String padded = String.format("%" + width + "s", text);
        return color.isEmpty() ? padded : color + padded + RESET;
    }

    /** The colour for a badness in [0, 1]: 0 is green, 1 is red. */
    private static String heat(double badness) {
        int index = (int) Math.round(Math.max(0, Math.min(1, badness)) * (HEAT.length - 1));
        return "[38;5;" + HEAT[index] + "m";
    }

    /** An amount in its currency's own symbol and grouping, to four decimals: a run costs cents. */
    private static String money(double amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
        format.setCurrency(Currency.getInstance(currency));
        format.setMinimumFractionDigits(4);
        format.setMaximumFractionDigits(4);
        return format.format(amount);
    }

    /** Milliseconds to the whole millisecond, with grouping: a fraction of one is noise. */
    private static String millis(double value) {
        return String.format("%,d", Math.round(value));
    }

    /** A mean that is whole prints as an integer with grouping; otherwise one decimal. */
    private static String count(double value) {
        return value == Math.rint(value) ? String.format("%,d", (long) value) : String.format("%,.1f", value);
    }
}
