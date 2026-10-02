/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

/**
 * The admission account for a single database connection pool. One instance per
 * datasource: future multi-datasource setups create multiple gates, each appearing as an
 * independent row in the system health snapshot under its own {@link #limiterName()}.
 *
 * <p>A permit is one held connection for the life of a job's handle. {@link Admission} takes
 * it as part of the job's demand; the provider then materializes the connection under it
 * ({@code DBResourceProvider.acquire}) and the permit returns when the grant is released at
 * close.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-15)
 */
public class DatabaseGate extends CountingGate {

    private final String datasourceName;

    /**
     * Creates a gate for a database pool.
     *
     * @param datasourceName short identifier used in the limiter name
     *                       ({@code "db:" + datasourceName}). For a single
     *                       pool deployment, {@code "default"} is fine;
     *                       for multi-datasource deployments use distinct
     *                       names like {@code "primary"}, {@code "analytics"},
     *                       {@code "vector"}.
     * @param maxConcurrent  maximum number of concurrent database connections
     *                       this gate admits.
     */
    public DatabaseGate(String datasourceName, int maxConcurrent) {
        super(maxConcurrent);
        this.datasourceName = datasourceName;
    }

    @Override
    public String limiterName() {
        return "db:" + datasourceName;
    }
}
