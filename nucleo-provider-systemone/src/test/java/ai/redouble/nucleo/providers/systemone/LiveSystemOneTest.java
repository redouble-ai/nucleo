/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.providers.systemone;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.decision.*;
import ai.redouble.nucleo.harness.llm.*;
import ai.redouble.nucleo.tools.deciding.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * The whole path against a real decision server: a decision submitted to the dispatcher
 * resolves to the pinned entry, is admitted on its account, reaches the server the
 * {@code systemone.live.url} property names, and comes back with answers a person can
 * check against the text. Skipped when the property is absent, since it needs a server
 * someone started; run it as
 * {@code mvn -pl nucleo-provider-systemone -am test -Dtest=LiveSystemOneTest -Dsystemone.live.url=http://127.0.0.1:8009}
 * against a Kev server.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-24)
 */
class LiveSystemOneTest {
    static final String MINUTES = "Decision: Meridian 3 and Kestrel 1 gravel retail and dealer prices go up by 20 percent from"
            + " 1 June 2026, on the prices in force in May. The Comet 2 is not affected: it carries no aluminium from Tai Han."
            + " Tune-up and bearing prices in the workshop are unchanged.";

    @Test
    void aDecisionAboutTheMinutesComesBackRightThroughTheWholeDoor() throws Exception {
        String url = System.getProperty("systemone.live.url");
        assumeTrue(url != null && !url.isBlank(), "no live server named: -Dsystemone.live.url");
        SystemOneTestSecrets.host = url;
        JobDispatcher.getInstance().start();
        LinkedHashMap<String, Question> questions = new LinkedHashMap<>();
        questions.put("comet_affected", Noul.of("Is the Comet 2 affected by the price change decided in this document?"));
        questions.put("kestrel_affected", Noul.of("Is the Kestrel 1 gravel affected by the price change decided in this document?"));
        questions.put("change", Choice.of("Which percentage is the decided price change?", "20 percent", "34 percent", "none"));
        questions.put("effective", Choice.of("From which date does the decided price change apply?", "1 June 2026", "10 May", "none"));
        DecisionCall call = new DecisionCall(Job.workflow("live", "live"), new DecisionRequest(MINUTES, questions));
        DecisionResponse response = JobDispatcher.getInstance().submit(call).get(60, TimeUnit.SECONDS);
        assertTrue(response.isSuccessful());
        assertTrue(response.noul("comet_affected").probability() < 0.2, "the Comet 2 is stated as not affected: " + response.getAnswers());
        assertTrue(response.noul("kestrel_affected").probability() > 0.8, "the Kestrel 1 is named: " + response.getAnswers());
        assertEquals("20 percent", response.choice("change").choice(), response.getAnswers().toString());
        assertEquals("1 June 2026", response.choice("effective").choice(), response.getAnswers().toString());
        assertNotNull(response.getActualInputTokens(), "the server reports what it read");
        assertTrue(response.getLatencyMs() >= 0);
        System.out.println("live decision on " + response.getServedModelId() + " in " + response.getLatencyMs() + " ms: " + response.getAnswers());
    }
}
