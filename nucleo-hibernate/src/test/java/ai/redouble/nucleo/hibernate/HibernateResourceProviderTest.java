/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.hibernate;

import ai.redouble.nucleo.harness.admission.*;
import com.zaxxer.hikari.*;
import org.hibernate.*;
import org.hibernate.cfg.*;
import org.junit.jupiter.api.*;

import java.sql.Connection;
import java.time.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HibernateResourceProvider} under the provider contract, on a factory whose sessions
 * would otherwise hand their connection back after every transaction; plus what is its own: a
 * commit's completion is awaited on the transaction's synchronization.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
class HibernateResourceProviderTest extends DBResourceProviderContract<Session, HibernateResourceProvider> {
    private SessionFactory sessionFactory;

    @Override
    protected HibernateResourceProvider newProvider(String name, HikariDataSource dataSource, int maxConcurrent) {
        if (sessionFactory == null) {
            Configuration configuration = new Configuration();
            configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource);
            sessionFactory = configuration.buildSessionFactory();
        }
        return new HibernateResourceProvider(name, sessionFactory, maxConcurrent, Duration.ofSeconds(5));
    }

    @AfterEach
    void closeFactory() {
        sessionFactory.close();
    }

    @Override
    protected void insert(Session session, String text) {
        session.createNativeMutationQuery("insert into note (text) values (:text)").setParameter("text", text).executeUpdate();
    }

    @Override
    protected boolean readOnly(Session session) {
        return session.doReturningWork(Connection::isReadOnly);
    }

    @Test
    void aReadOnlyHandleOpensAReadOnlySession() {
        DBManagedResource<Session> handle = provider.acquire(true);
        assertTrue(handle.unwrap().isDefaultReadOnly(), "entities it loads are read-only too");
        provider.close(handle);
    }

    @Test
    void aFactoryBuiltAfterTheProviderIsTheOneItsSessionsOpenFrom() {
        AtomicReference<SessionFactory> later = new AtomicReference<>();
        AbstractHibernateResourceProvider<Session> lateBound = new AbstractHibernateResourceProvider<>("late", later::get, 1, Duration.ofSeconds(5)) {
            @Override
            protected Session expose(Session session) {
                return session;
            }
        };
        later.set(sessionFactory);
        DBManagedResource<Session> handle = lateBound.acquire(false);
        assertSame(sessionFactory, handle.unwrap().getSessionFactory(), "the factory is asked for at acquire, not at construction");
        lateBound.close(handle);
    }

    @Test
    void awaitCompletionReturnsOnceTheCommitCompleted() {
        DBManagedResource<Session> handle = provider.acquire(false);
        provider.begin(handle);
        insert(handle.unwrap(), "a");
        provider.commit(handle);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> provider.awaitCompletion(handle),
                "the synchronization fired at commit, so the wait is already over");
        provider.close(handle);
    }
}
