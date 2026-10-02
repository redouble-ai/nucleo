/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.quarkus;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.observability.*;
import io.quarkus.runtime.*;
import jakarta.annotation.*;
import jakarta.enterprise.context.*;
import jakarta.enterprise.event.*;

/**
 * The runtime's lifecycle, bound to the CDI container: the {@code nucleo.*} properties bind
 * onto the registered settings, then the dispatcher starts, before the HTTP router accepts a
 * request; on shutdown it drains. Two things are wired here and nowhere else: the event log,
 * so every job's states show up in the process log, and the cost ledger, subscribed for every
 * finished job's calls and registered as the dispatcher's spend gate, so a run's spend is
 * readable while it runs and a budget is enforced before any job acquires a resource. A host
 * that records usage elsewhere injects this bean, reads {@link #dispatcher()} and subscribes
 * its own observers; the same runtime serves either.
 *
 * <p>This is the Quarkus half of what the Spring starter's {@code NucleoRuntime} does, on the
 * container's own {@link StartupEvent} and {@link PreDestroy} instead of Spring's
 * {@code SmartLifecycle}. The dispatcher is a process-wide singleton either way, so the two
 * hosts never both own it in one process.
 *
 * <p>A dev-mode live reload and a test-mode profile switch both stop and restart the application in
 * the same JVM. The dispatcher singleton, and the cost ledger held here, live in the classloader
 * that persists across those restarts, and the dispatcher does not restart once shut down (its
 * message bus does not reopen). So the lifecycle here is process-scoped, not
 * application-instance-scoped: the dispatcher starts once and its subscribers are registered once, a
 * restart's fresh {@link StartupEvent} finds it already running and does nothing, and shutdown is
 * skipped on a reload or a test restart and runs only when the JVM exits. The ledger is static for
 * the same reason, so the instance every restart's bean exposes is the one that is actually
 * subscribed.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-22)
 */
@ApplicationScoped
public class NucleoRuntime {
    private static final CostLedger LEDGER = new CostLedger();
    private final JobDispatcher dispatcher = JobDispatcher.getInstance();

    /**
     * Binds properties and starts the dispatcher when the container starts. A high priority so
     * it runs before observers a host registers on its own {@link StartupEvent}, which may
     * submit a first job. A no-op when the dispatcher is already running, which is the case on a
     * dev-mode reload or a test-mode restart.
     */
    void onStart(@Observes @Priority(1) StartupEvent event) {
        if (dispatcher.isRunning()) {
            return;
        }
        QuarkusSettingsBinder.bind();
        dispatcher.start();
        dispatcher.subscribe(new EventLogger(), JobEvent.class);
        dispatcher.subscribe(LEDGER, JobEvent.class);
        dispatcher.registerSpendGate(LEDGER);
    }

    @PreDestroy
    void stop() {
        // Only a NORMAL run ends the process here. Dev mode fires this on every live reload and the
        // test extension on every profile switch, both restarting the application in the same JVM;
        // shutting the singleton dispatcher down then would leave the next start unable to restart
        // it (its message bus does not reopen) and would block the restart while it drains, which in
        // tests leaves the previous HTTP server still holding its port. So it is left running across
        // reloads and test restarts, and torn down only when the JVM is actually exiting.
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return;
        }
        dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
    }

    public boolean isRunning() {
        return dispatcher.isRunning();
    }

    public JobDispatcher dispatcher() {
        return dispatcher;
    }

    public CostLedger ledger() {
        return LEDGER;
    }
}
