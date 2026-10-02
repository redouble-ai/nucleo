/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.hibernate;

import org.hibernate.*;

import java.time.*;

/**
 * The Hibernate provider a job uses directly: it unwraps the {@link Session}, connected and
 * held for the job's whole life.
 *
 * <pre>
 * HibernateResourceProvider db = new HibernateResourceProvider("default", sessionFactory, 20, Duration.ofSeconds(30));
 * DBResourceProviders.register(db);
 * </pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
public class HibernateResourceProvider extends AbstractHibernateResourceProvider<Session> {

    public HibernateResourceProvider(String name, SessionFactory sessionFactory, int maxConcurrent, Duration completionTimeout) {
        super(name, () -> sessionFactory, maxConcurrent, completionTimeout);
    }

    @Override
    protected Session expose(Session session) {
        return session;
    }
}
