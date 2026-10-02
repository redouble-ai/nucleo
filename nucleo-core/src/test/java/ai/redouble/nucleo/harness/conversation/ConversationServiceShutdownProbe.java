/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The forked-JVM half of {@link ConversationServiceShutdownTest}: exercises
 * {@link ConversationService}'s shutdown in a JVM this process can afford to poison.
 * Stopping the singleton is final - after it, the service refuses all new work - so
 * this can never run inside the shared test JVM. Exit 0 means every check held;
 * any other exit carries the failure on stderr.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-18)
 */
public final class ConversationServiceShutdownProbe {
    private ConversationServiceShutdownProbe() {}

    private static final class ProbeThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        ProbeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            // The probe drives ConversationService directly; the thinker is only an identity.
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        ConversationService service = ConversationService.getInstance();
        // A warm, retained holder that shutdown must clear from memory
        ProbeThinker thinker = new ProbeThinker(Job.workflow("shutdown-probe", "shutdown-probe"));
        String minted = thinker.mintConversationId();
        service.obtainConversation(minted, thinker, StringResponseHandler.instance, false).join();
        service.release(thinker, Duration.ofMinutes(5));
        check(service.getStats().total() == 1, "the retained holder is in memory before the stop");

        long begun = System.currentTimeMillis();
        service.stop();
        long took = System.currentTimeMillis() - begun;

        check(service.isStopping(), "the service reports stopping after stop()");
        check(took < 4_900, "an idle cleanup executor terminates within the 5s grace, took " + took + "ms");
        check(service.getStats().total() == 0, "memory is cleared - the retained holder is gone");
        ProbeThinker late = new ProbeThinker(Job.workflow("shutdown-probe", "shutdown-probe-late"));
        try {
            service.obtainConversation(late.getConversationId(), late, StringResponseHandler.instance, false).join();
            throw new IllegalStateException("FAILED: a stopping service accepted new work");
        }
        catch (CompletionException refused) {
            check(refused.getCause() instanceof IllegalStateException,
                    "new work is refused with IllegalStateException, got " + refused.getCause());
        }
        service.stop();
        check(service.isStopping(), "a second stop() is a safe no-op");
        System.exit(0);
    }

    private static void check(boolean condition, String rule) {
        if (!condition) {
            throw new IllegalStateException("FAILED: " + rule);
        }
        System.out.println("OK: " + rule);
    }
}
