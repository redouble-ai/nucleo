/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness;

import java.time.*;

/**
 * A request to fire a heartbeat: INTENT ONLY. Deliberately carries no principal and no
 * scope - there is nothing on this record to forge. The framework mints the stored
 * {@link Heartbeat} (which does carry authority) at exactly two places: the
 * {@code JobContext.publish} capture path, which stamps the publishing orchestrator's
 * identity and sealed guard, and {@code JobDispatcher.scheduleHeartbeat}, the
 * authority-bearing boot/operator entry that demands a principal the way
 * {@code Job.workflow} does. Handing this record to the raw message bus does nothing:
 * it is not an event, and the Heart only consumes framework-minted {@link Heartbeat}s.
 *
 * @param jobClass       the job to construct and dispatch on fire; must expose an
 *                       {@code (Identifiable)} constructor
 * @param heartbeatId    the store-entry identity, mandatory; re-using one rebinds
 *                       (most-recent-wins, audited via HeartbeatRebound)
 * @param conversationId optional: when set, the fired job must implement
 *                       {@link ConversationCarrier}; the Heart hands it the id through
 *                       {@code setConversationId} and the carrier hydrates the
 *                       conversation inside its own execution. A non-carrier job class
 *                       with a conversation id fails the fire.
 * @param runAt          when to fire
 * @param input          optional payload applied to the constructed job; fails loudly
 *                       on type mismatch
 * @param recurrence     null for single-shot
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-29)
 */
public record ScheduleHeartbeat(Class<? extends Job<?>> jobClass, String heartbeatId, String conversationId,
                                Instant runAt, Object input, Recurrence recurrence) {

    public ScheduleHeartbeat {
        if (jobClass == null) {
            throw new IllegalArgumentException("A heartbeat must name the job class to fire");
        }
        if (heartbeatId == null || heartbeatId.isBlank()) {
            throw new IllegalArgumentException("A heartbeat must carry a non-blank heartbeatId - "
                    + "publishers without a stable handle generate one (UUID.randomUUID().toString())");
        }
        if (runAt == null) {
            throw new IllegalArgumentException("A heartbeat must say when to fire");
        }
    }
}
