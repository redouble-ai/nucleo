/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.admission;

import ai.redouble.nucleo.harness.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CountingDBResourceProvider}: admission counts the jobs that declared it against the
 * host's bound, and each admitted job takes connections from its own stack's pool whenever it
 * likes. Measured on a real pool: however the jobs take and return connections, the pool never
 * has more out for them than the bound. The handle carries nothing, the transaction verbs do
 * nothing, and a handle of another provider is refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class CountingDBResourceProviderTest {
    private HikariDataSource pool;
    private CountingDBResourceProvider provider;

    @BeforeEach
    void start() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:hsqldb:mem:counting");
        config.setUsername("SA");
        config.setPassword("");
        config.setMaximumPoolSize(6);
        pool = new HikariDataSource(config);
        provider = new CountingDBResourceProvider("counted", 2);
    }

    @AfterEach
    void stop() {
        pool.close();
    }

    @Test
    void theAccountIsNamedAfterTheDatasource_andSizedByTheHost() {
        assertEquals("counted", provider.name());
        DatabaseGate gate = (DatabaseGate) provider.admission();
        assertEquals("db:counted", gate.limiterName());
        assertEquals(2, gate.capacity());
    }

    @Test
    void theHandleCarriesNothing_andTheVerbsDoNothing() {
        DBManagedResource<Void> handle = provider.acquire(false);
        assertNull(handle.unwrap(), "the job reaches its connection through its own stack");
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), "nothing was checked out for it");
        provider.begin(handle);
        provider.commit(handle);
        provider.awaitCompletion(handle);
        provider.rollback(handle);
        provider.abort(handle);
        provider.close(handle);
        provider.close(handle);
    }

    @Test
    void aHandleFromAnotherProviderIsRefused() {
        DBManagedResource<Void> foreign = new CountingDBResourceProvider("other", 1).acquire(false);
        assertThrows(IllegalArgumentException.class, () -> provider.close(foreign));
    }

    @Test
    void jobsTakeConnectionsFromTheirOwnPool_neverMoreAtOnceThanTheBound() throws Exception {
        JobDispatcher.getInstance().start();
        AtomicInteger peak = new AtomicInteger();
        Identifiable root = Job.workflow("counting", "counting");
        List<JobHandle<Integer>> handles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            handles.add(JobDispatcher.getInstance().submit(new PoolUsingJob(root, peak)));
        }
        for (JobHandle<Integer> handle : handles) {
            assertEquals(2, handle.get(30, TimeUnit.SECONDS), "each job ran its two statements");
        }
        assertTrue(peak.get() <= 2, "the pool never had more out for the jobs than the bound, saw " + peak.get());
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    /** Declares the provider, then takes a connection from the pool twice, one at a time, as its stack would per transaction. */
    private final class PoolUsingJob extends AbstractJob<Integer> {
        private final AtomicInteger peak;

        private PoolUsingJob(Identifiable parent, AtomicInteger peak) {
            super(parent, "pool-user");
            this.peak = peak;
            setTimeout(Duration.ofSeconds(30));
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.addProvider(provider);
            return req;
        }

        @Override
        public Integer execute(JobResources resources, JobContext<Integer> context) throws Exception {
            int statements = 0;
            for (int i = 0; i < 2; i++) {
                try (Connection c = pool.getConnection(); Statement s = c.createStatement()) {
                    peak.accumulateAndGet(pool.getHikariPoolMXBean().getActiveConnections(), Math::max);
                    s.execute("values 1");
                    statements++;
                    Thread.sleep(30);
                }
            }
            return statements;
        }
    }
}
