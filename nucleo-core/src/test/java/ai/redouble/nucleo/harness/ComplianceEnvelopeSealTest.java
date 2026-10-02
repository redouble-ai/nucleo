/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import ai.redouble.nucleo.harness.models.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dispatcher's envelope seal: refusing default before any seal (without sealing),
 * one seal permanent, second seal refused.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-28)
 */
class ComplianceEnvelopeSealTest {

    @AfterEach
    void reset() {
        JobDispatcher.getInstance().resetComplianceEnvelopeForTests();
    }

    @Test
    void unsealedReadsReturnTheRefusingDefaultWithoutSealing() {
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.resetComplianceEnvelopeForTests();
        ComplianceEnvelope beforeSeal = dispatcher.getComplianceEnvelope();
        assertFalse(beforeSeal.permits(TestModels.requiringLax()), "default refuses data share");
        // The read did NOT seal: a later legitimate seal still lands
        DefaultComplianceEnvelope permitting = new DefaultComplianceEnvelope();
        permitting.setAllowDataShare(true);
        dispatcher.sealComplianceEnvelope(permitting);
        assertTrue(dispatcher.getComplianceEnvelope().permits(TestModels.requiringLax()));
    }

    @Test
    void secondSealIsRefused() {
        JobDispatcher dispatcher = JobDispatcher.getInstance();
        dispatcher.resetComplianceEnvelopeForTests();
        dispatcher.sealComplianceEnvelope(new DefaultComplianceEnvelope());
        assertThrows(IllegalStateException.class,
                () -> dispatcher.sealComplianceEnvelope(new DefaultComplianceEnvelope()));
        assertThrows(IllegalArgumentException.class, () -> {
            dispatcher.resetComplianceEnvelopeForTests();
            dispatcher.sealComplianceEnvelope(null);
        });
    }
}
