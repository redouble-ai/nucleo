/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.admission.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.sql.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpringTransactionResourceProvider} over a {@link DataSourceTransactionManager}, under
 * the provider contract with every row written through {@link JdbcTemplate} - the way an
 * application's own beans write - plus what is its own: data access outside a transaction
 * lands on the held connection too, the job's thread is left with no binding once the handle
 * closes, and a close from another thread still returns the connection.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class SpringTransactionResourceProviderTest extends DBResourceProviderContract<TransactionStatus, SpringTransactionResourceProvider> {

    @Override
    protected SpringTransactionResourceProvider newProvider(String name, HikariDataSource dataSource, int maxConcurrent) {
        return new SpringTransactionResourceProvider(name, new DataSourceTransactionManager(dataSource), maxConcurrent);
    }

    @Override
    protected void insert(TransactionStatus status, String text) {
        new JdbcTemplate(dataSource).update("insert into note (text) values (?)", text);
    }

    @Override
    protected boolean readOnly(TransactionStatus status) throws SQLException {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            return connection.isReadOnly();
        }
        finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    @Test
    void accessOutsideATransactionLandsOnTheHeldConnection() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        assertNull(handle.unwrap(), "no transaction under way");
        insert(handle.unwrap(), "a");
        assertEquals(1, active(), "the template found the held connection instead of the pool");
        assertEquals(1, rows(), "outside a transaction the statement committed by itself");
        provider.close(handle);
    }

    @Test
    void aRequiresNewTransactionTakesASecondConnection_andHandsItBackWhenItEnds() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "outer");
        TransactionTemplate requiresNew = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int[] during = new int[1];
        requiresNew.executeWithoutResult(status -> {
            insert(status, "inner");
            during[0] = active();
        });
        assertEquals(2, during[0], "the new transaction suspends the job's and runs on a connection of its own");
        assertEquals(1, active(), "which goes back when it ends; the job's connection is bound again");
        provider.rollback(handle);
        assertEquals(1, rows(), "the inner transaction committed on its own, the outer one rolled back");
        provider.close(handle);
    }

    @Test
    void aClosedHandleLeavesTheThreadWithNoBinding() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        provider.begin(handle);
        assertNotNull(handle.unwrap(), "the transaction under way");
        insert(handle.unwrap(), "a");
        provider.close(handle);
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), "nothing bound");
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive(), "no transaction state left");
        assertEquals(0, rows(), "the open transaction was rolled back through the manager");
    }

    @Test
    void anAbortedHandleClosedOnItsThreadLeavesNoTransactionState() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.abort(handle);
        assertThrows(Exception.class, () -> insert(handle.unwrap(), "b"), "the severed connection runs nothing more");
        provider.close(handle);
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), "nothing bound");
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive(), "no transaction state left");
        assertEquals(0, active());
    }

    @Test
    void aCloseFromAnotherThreadReturnsTheConnection() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        CompletableFuture.runAsync(() -> provider.close(handle), Executors.newVirtualThreadPerTaskExecutor()).get(5, TimeUnit.SECONDS);
        assertEquals(0, active(), "the connection went back from the timeout's thread");
        assertEquals(0, rows(), "and what the job had not committed did not survive");
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.unbindResourceIfPossible(dataSource);
    }

    @Test
    void theTransactionVerbsRunOnlyOnTheJobsThread() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> CompletableFuture.runAsync(() -> provider.begin(handle), Executors.newVirtualThreadPerTaskExecutor()).get(5, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, refused.getCause(), "a Spring transaction is bound to the thread that holds the connection");
        provider.close(handle);
    }
}
