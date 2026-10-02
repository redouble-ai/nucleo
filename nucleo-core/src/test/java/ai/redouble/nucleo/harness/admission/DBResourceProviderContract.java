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
 * The {@link DBResourceProvider} contract, run against a real database behind a real pool: an
 * in-process HSQLDB (MVCC, so an uncommitted row is invisible rather than locking the reader)
 * behind Hikari, whose live active count is what "one permit is one held connection" is
 * measured by. A provider's test extends this class and says how its handle's value writes a
 * row and reports read-only; every rule below then holds for it. Ships in the test-jar, so the
 * providers of other modules run the same rules.
 *
 * @param <T> what a job unwraps from the provider's handle
 * @param <P> the provider under test
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public abstract class DBResourceProviderContract<T, P extends DBResourceProvider<T>> {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    protected HikariDataSource dataSource;
    protected P provider;

    /** The provider under test over the datasource, with the given admission bound. */
    protected abstract P newProvider(String name, HikariDataSource dataSource, int maxConcurrent);

    /** Writes a row with the text into {@code note} through what a job unwraps. */
    protected abstract void insert(T value, String text) throws Exception;

    /** Whether the connection behind what a job unwraps is read-only. */
    protected abstract boolean readOnly(T value) throws Exception;

    @BeforeEach
    void startDatabase() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:hsqldb:mem:contract" + DATABASES.incrementAndGet() + ";hsqldb.tx=mvcc");
        config.setUsername("SA");
        config.setPassword("");
        config.setMaximumPoolSize(4);
        config.setPoolName("contract-pool");
        dataSource = new HikariDataSource(config);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table note (text varchar(100))");
        }
        provider = newProvider("contract", dataSource, 2);
    }

    @AfterEach
    void stopDatabase() {
        dataSource.close();
    }

    protected int active() {
        return dataSource.getHikariPoolMXBean().getActiveConnections();
    }

    protected int rows() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("select count(*) from note")) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void theAdmissionAccountIsNamedAfterTheDatasource_andSizedByTheHost() {
        assertEquals("contract", provider.name());
        DatabaseGate gate = (DatabaseGate) provider.admission();
        assertEquals("db:contract", gate.limiterName());
        assertEquals(2, gate.capacity(), "the bound the host passed");
    }

    @Test
    void aHandleHoldsOneConnectionFromAcquireToClose() throws Exception {
        assertEquals(0, active(), "nothing checked out before the job");
        DBManagedResource<T> handle = provider.acquire(false);
        assertEquals(1, active(), "acquire checks the connection out, before any statement runs");
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.commit(handle);
        provider.awaitCompletion(handle);
        assertEquals(1, active(), "a commit does not hand the connection back mid-job");
        provider.close(handle);
        assertEquals(0, active(), "close returns it");
    }

    @Test
    void aCommitIsVisibleToOtherConnectionsOnceAwaitCompletionReturns() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        assertEquals(0, rows(), "uncommitted, another connection does not see it");
        provider.commit(handle);
        provider.awaitCompletion(handle);
        assertEquals(1, rows(), "committed and awaited, another connection sees it");
        provider.close(handle);
    }

    @Test
    void severalTransactionsRunOnTheOneConnection() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        for (String text : List.of("a", "b")) {
            provider.begin(handle);
            insert(handle.unwrap(), text);
            provider.commit(handle);
            provider.awaitCompletion(handle);
            assertEquals(1, active(), "every transaction runs on the connection acquire checked out");
        }
        assertEquals(2, rows());
        provider.close(handle);
    }

    @Test
    void rollbackDiscardsTheTransaction_andIsIdempotent() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.rollback(handle);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.rollback(handle);
        provider.rollback(handle);
        assertEquals(0, rows(), "rolled back");
        provider.close(handle);
    }

    @Test
    void closeRollsBackAnOpenTransaction_andIsIdempotent() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.close(handle);
        provider.close(handle);
        assertEquals(0, rows(), "the open transaction was rolled back");
        assertEquals(0, active(), "the connection went back once");
    }

    @Test
    void abortSeversTheConnection_andACloseAfterItStillHandsItBack() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.abort(handle);
        provider.abort(handle);
        assertThrows(Exception.class, () -> insert(handle.unwrap(), "b"), "an aborted connection runs nothing more");
        provider.close(handle);
        assertEquals(0, active(), "the aborted connection left the pool's active set");
        assertEquals(0, rows(), "nothing the aborted job wrote was committed");
    }

    @Test
    void aCloseStraightAfterAnAbortReportsWhatThePoolSaid_andStillHandsTheConnectionBack() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.abort(handle);
        Throwable reported = null;
        try {
            provider.close(handle);
        }
        catch (RuntimeException e) {
            reported = e;
        }
        assertTrue(reported == null || reported instanceof ai.redouble.nucleo.harness.errors.UncorrectableRuntimeLLMException,
                "a pool that cannot reset a severed connection is reported as an uncorrectable failure, never a raw one: " + reported);
        assertEquals(0, active(), "the connection left the pool's active set either way");
        assertEquals(0, rows(), "nothing the aborted job wrote was committed");
    }

    @Test
    void theReadOnlyHintReachesTheConnection() throws Exception {
        DBManagedResource<T> reader = provider.acquire(true);
        assertTrue(readOnly(reader.unwrap()), "a read-only job gets a read-only connection");
        provider.close(reader);
        DBManagedResource<T> writer = provider.acquire(false);
        assertFalse(readOnly(writer.unwrap()), "the hint does not stick to the pooled connection");
        provider.close(writer);
    }

    @Test
    void aThreadWaitingForCompletionIsReleasedByClose() throws Exception {
        DBManagedResource<T> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        CountDownLatch returned = new CountDownLatch(1);
        Thread waiter = Thread.ofVirtual().start(() -> {
            provider.awaitCompletion(handle);
            returned.countDown();
        });
        Thread.sleep(100);
        provider.close(handle);
        assertTrue(returned.await(5, TimeUnit.SECONDS), "the waiter was released by the close");
        waiter.join();
    }

    @Test
    void aHandleFromAnotherProviderIsRefused() {
        P other = newProvider("other", dataSource, 1);
        DBManagedResource<T> foreign = other.acquire(false);
        assertThrows(IllegalArgumentException.class, () -> provider.commit(foreign), "a handle belongs to the provider that made it");
        other.close(foreign);
    }

    @Test
    void throughTheDispatcherJobsNeverHoldMoreConnectionsThanTheBound() throws Exception {
        JobDispatcher.getInstance().start();
        AtomicInteger peak = new AtomicInteger();
        Identifiable root = Job.workflow("db-contract", "db-contract");
        List<JobHandle<String>> handles = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            handles.add(JobDispatcher.getInstance().submit(new WritingJob(root, "row" + i, peak)));
        }
        for (JobHandle<String> handle : handles) {
            assertEquals("written", handle.get(30, TimeUnit.SECONDS));
        }
        assertEquals(6, rows(), "every job committed its row");
        assertTrue(peak.get() <= 2, "the gate's two permits bound the connections held at once, saw " + peak.get());
        assertEquals(0, active(), "every connection went back");
    }

    /** One job of the dispatcher test: declares the provider and a transaction, writes one row. */
    private final class WritingJob extends AbstractJob<String> {
        private final String text;
        private final AtomicInteger peak;

        private WritingJob(Identifiable parent, String text, AtomicInteger peak) {
            super(parent, "db-contract-writer");
            this.text = text;
            this.peak = peak;
            setTimeout(Duration.ofSeconds(30));
        }

        @Override
        public JobRequirements getRequirements() {
            JobRequirements req = new JobRequirements();
            req.addProvider(provider);
            req.setRequiresTransaction(true);
            return req;
        }

        @Override
        public String execute(JobResources resources, JobContext<String> context) throws Exception {
            peak.accumulateAndGet(active(), Math::max);
            insert(resources.get(provider), text);
            Thread.sleep(50);
            peak.accumulateAndGet(active(), Math::max);
            return "written";
        }
    }
}
