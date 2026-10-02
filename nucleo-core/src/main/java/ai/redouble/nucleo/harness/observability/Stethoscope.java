/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.events.heartbeat.*;
import ai.redouble.nucleo.harness.*;

import java.util.function.*;

/**
 * Convenience base for external observers following the heart's lifecycle - audit
 * loggers, dashboards, metrics exporters. Every heartbeat event passes the predicate and is
 * routed to the hook for its type; override the moments you care about, since a hook not
 * overridden does nothing. {@link #attach} subscribes once and refuses a second attach with
 * {@link IllegalStateException}; {@link #detach} unsubscribes and may be called again. Each
 * attached Stethoscope rides the bus's per-subscriber queue, so a slow one (remote audit
 * sink, DB) backs up only itself.
 *
 * <p>The Heart is deliberately NOT a Stethoscope: the Heart does work (stores,
 * constructs, dispatches); a Stethoscope only watches. Keeping audit logic out of the
 * Heart's subscriber callback is the point of the split.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public abstract class Stethoscope implements JobObserver<HeartbeatEvent> {
    private MessageBus.Subscription subscription;

    public void onScheduleRequested(HeartbeatRequested e) {}

    public void onScheduled(HeartbeatScheduled e) {}

    public void onCancelled(CancelHeartbeat e) {}

    public void onRebound(HeartbeatRebound e) {}

    public void onFired(HeartbeatFired e) {}

    public void onFireFailed(HeartbeatFireFailed e) {}

    /** Wires the bus subscription. Symmetric with {@link #detach()}. */
    public final void attach(JobDispatcher dispatcher) {
        if (subscription != null) {
            throw new IllegalStateException("Stethoscope already attached");
        }
        subscription = dispatcher.subscribe(this, HeartbeatEvent.class);
    }

    public final void detach() {
        if (subscription != null) {
            subscription.unsubscribe();
            subscription = null;
        }
    }

    @Override
    public final Predicate<HeartbeatEvent> getPredicate() {
        return event -> true;
    }

    @Override
    public final void observe(HeartbeatEvent event) {
        switch (event) {
            case HeartbeatRequested e -> onScheduleRequested(e);
            case HeartbeatScheduled e -> onScheduled(e);
            case CancelHeartbeat e -> onCancelled(e);
            case HeartbeatRebound e -> onRebound(e);
            case HeartbeatFired e -> onFired(e);
            case HeartbeatFireFailed e -> onFireFailed(e);
        }
    }
}
