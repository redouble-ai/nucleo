/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.observability;

import ai.redouble.nucleo.harness.*;
import io.micrometer.core.instrument.*;
import org.slf4j.*;

/**
 * Global observer that forwards job lifecycle events to a Micrometer {@link MeterRegistry}.
 *
 * <p>Registered once, observes all workflows. What gets recorded is entirely up to
 * the {@link MeterCustomizer}. The observer only handles event dispatch: every event reaches
 * the customizer, and one that throws is logged at WARN while the observer goes on.
 *
 * <p>Usage:
 * <pre>{@code
 * MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
 * MeterCustomizer customizer = new DefaultMeterCustomizer();
 * MicrometerObserver observer = new MicrometerObserver(registry, customizer);
 * messageBus.subscribe(Job.class, JobEvent.class, observer);
 * }</pre>
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public class MicrometerObserver implements JobObserver<JobEvent> {
    private static final Logger log = LoggerFactory.getLogger(MicrometerObserver.class);
    private final MeterRegistry registry;
    private final MeterCustomizer customizer;

    /**
     * Creates a global observer that forwards events to a Micrometer registry.
     *
     * @param registry the meter registry to record metrics on
     * @param customizer callback that decides what metrics to record
     */
    public MicrometerObserver(MeterRegistry registry, MeterCustomizer customizer) {
        this.registry = registry;
        this.customizer = customizer;
    }

    @Override
    public void observe(JobEvent event) {
        try {
            customizer.record(event, registry);
        } catch (Exception e) {
            log.warn("MeterCustomizer threw for event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
