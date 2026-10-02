/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.jdbc;

import ai.redouble.nucleo.harness.admission.*;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;

import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link JdbcResourceProvider} under the provider contract, plus what is its own: a connection
 * outside a transaction is in auto-commit, and the pool gets back the state it handed out.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class JdbcResourceProviderTest extends DBResourceProviderContract<Connection, JdbcResourceProvider> {

    @Override
    protected JdbcResourceProvider newProvider(String name, HikariDataSource dataSource, int maxConcurrent) {
        return new JdbcResourceProvider(name, dataSource, maxConcurrent);
    }

    @Override
    protected void insert(Connection connection, String text) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("insert into note (text) values (?)")) {
            s.setString(1, text);
            s.executeUpdate();
        }
    }

    @Override
    protected boolean readOnly(Connection connection) throws SQLException {
        return connection.isReadOnly();
    }

    @Test
    void outsideATransactionEveryStatementCommitsByItself() throws Exception {
        DBManagedResource<Connection> handle = provider.acquire(false);
        insert(handle.unwrap(), "a");
        assertEquals(1, rows(), "no begin, auto-commit: the row is visible at once");
        provider.close(handle);
    }

    @Test
    void thePoolGetsBackTheStateItHandedOut() throws Exception {
        DBManagedResource<Connection> handle = provider.acquire(true);
        provider.begin(handle);
        provider.close(handle);
        try (Connection next = dataSource.getConnection()) {
            assertTrue(next.getAutoCommit(), "auto-commit restored");
            assertFalse(next.isReadOnly(), "read-only restored");
        }
    }
}
