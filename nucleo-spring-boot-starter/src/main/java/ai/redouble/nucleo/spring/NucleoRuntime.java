/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.spring;

import ai.redouble.nucleo.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.observability.*;
import org.springframework.boot.*;
import org.springframework.context.*;

/**
 * The runtime's lifecycle, bound to the application context: the dispatcher starts before the
 * web server accepts requests and drains after it stops accepting them. Two things are wired
 * here and nowhere else: the event log, so every job's states show up in the process log,
 * and the cost ledger, subscribed for every finished job's calls and registered as the
 * dispatcher's spend gate, so a run's spend is readable while it runs and a budget is
 * enforced before any job acquires a resource. A host that records usage elsewhere or
 * exports metrics declares its own {@code NucleoRuntime} bean and subscribes its observers
 * in the same place; the auto-configuration steps aside for it.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-11)
 */
public class NucleoRuntime implements SmartLifecycle {
    private final JobDispatcher dispatcher = JobDispatcher.getInstance();
    private final CostLedger ledger = new CostLedger();
    private final boolean discovering;
    private volatile boolean started;

    public NucleoRuntime(ApplicationArguments arguments) {
        this.discovering = arguments.getNonOptionArgs().contains(NucleoSpringApplication.DISCOVER);
    }

    /**
     * The catalog discovery runs before a catalog, a picker or a dispatcher exist, and seals
     * its own permitting envelope to ask what the account can call; a started dispatcher
     * would have sealed the refusing default first. So in discover mode the runtime stays
     * down.
     */
    @Override
    public boolean isAutoStartup() {
        return !discovering;
    }

    @Override
    public void start() {
        dispatcher.start();
        dispatcher.subscribe(new EventLogger(), JobEvent.class);
        dispatcher.subscribe(ledger, JobEvent.class);
        dispatcher.registerSpendGate(ledger);
        started = true;
    }

    /** Drains the dispatcher this bean started; one it did not start is not its to stop. */
    @Override
    public void stop() {
        if (started) {
            started = false;
            dispatcher.shutdown(JobDispatcher.DEFAULT_SHUTDOWN_TIMEOUT_MS);
        }
    }

    /**
     * Whether this bean started the dispatcher and it still runs. The dispatcher is a
     * process-wide singleton: reporting its state regardless would have the context stop a
     * dispatcher it never started when it closes.
     */
    @Override
    public boolean isRunning() {
        return started && dispatcher.isRunning();
    }

    /**
     * A lower phase than the web server's (Boot puts it just under the default), so the
     * dispatcher starts before the first request can arrive and stops after the last one
     * has drained.
     */
    @Override
    public int getPhase() {
        return 0;
    }

    public JobDispatcher dispatcher() {
        return dispatcher;
    }

    public CostLedger ledger() {
        return ledger;
    }
}
