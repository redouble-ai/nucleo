/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.harness.admission.*;
import com.zaxxer.hikari.*;
import jakarta.persistence.*;
import org.hibernate.*;
import org.hibernate.cfg.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.sql.Connection;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SpringTransactionResourceProvider} over a {@link JpaTransactionManager} on Hibernate,
 * under the provider contract with every row written through {@link JdbcTemplate}: plain JDBC
 * beside JPA, the case the datasource binding between transactions exists for. Plus what is
 * its own: the entity manager a bean gets is the held session, and JPA and JDBC work in one
 * transaction commit together.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class SpringJpaTransactionResourceProviderTest extends DBResourceProviderContract<TransactionStatus, SpringTransactionResourceProvider> {
    private SessionFactory sessionFactory;

    @Override
    protected SpringTransactionResourceProvider newProvider(String name, HikariDataSource dataSource, int maxConcurrent) {
        if (sessionFactory == null) {
            Configuration configuration = new Configuration();
            configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource);
            sessionFactory = configuration.buildSessionFactory();
        }
        JpaTransactionManager manager = new JpaTransactionManager(sessionFactory);
        manager.setDataSource(dataSource);
        manager.setJpaDialect(new HibernateJpaDialect());
        return new SpringTransactionResourceProvider(name, manager, maxConcurrent);
    }

    @AfterEach
    void closeFactory() {
        sessionFactory.close();
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
    void jpaAndJdbcInOneTransactionRunOnTheHeldConnection_andCommitTogether() throws Exception {
        DBManagedResource<TransactionStatus> handle = provider.acquire(false);
        EntityManager entityManager = EntityManagerFactoryUtils.getTransactionalEntityManager(sessionFactory);
        assertNotNull(entityManager, "a bean asking for the transactional entity manager gets the held session");
        provider.begin(handle);
        entityManager.createNativeQuery("insert into note (text) values ('jpa')").executeUpdate();
        insert(handle.unwrap(), "jdbc");
        assertEquals(1, active(), "both ran on the one connection");
        assertEquals(0, rows(), "not yet committed");
        provider.commit(handle);
        assertEquals(2, rows(), "committed together");
        provider.close(handle);
        assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty(), "nothing left bound");
    }

    @Test
    void aManagerOverAnotherKindOfResourceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpringTransactionResourceProvider("refused", new ForeignResourceManager(), 1));
    }

    /** A resource manager whose resource is neither a datasource nor an entity manager factory. */
    private static final class ForeignResourceManager extends AbstractPlatformTransactionManager implements ResourceTransactionManager {
        @Override
        public Object getResourceFactory() {
            return new Object();
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
