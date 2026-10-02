/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.schema.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the reference guardrails' own validation logic, independent of dispatch
 * enforcement (which {@code GuardrailEnforcementTest} covers).
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-18)
 */
public class SampleGuardrailsTest {
    private static final Identifiable TEST_ROOT = Job.workflow("test-user", "sample-guardrails-test");

    record CustomerRecord(String name, String note) {
    }

    @Test
    void piiDetectsSsnInsideSerializedPojoFields() {
        PIIDetectionGuardrail guard = new PIIDetectionGuardrail(TEST_ROOT);
        GuardrailException e = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("A. Customer", "SSN on file: 123-45-6789")));
        assertTrue(e.getMessage().contains("SSN"), e.getMessage());
    }

    @Test
    void piiDetectsEmailAndPassesCleanContent() throws Exception {
        PIIDetectionGuardrail guard = new PIIDetectionGuardrail(TEST_ROOT);
        assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("A. Customer", "reach me at a.customer@example.com")));
        guard.validate(new CustomerRecord("A. Customer", "no sensitive content here"));
        guard.validate(null);
    }

    @Test
    void payloadSizeCapMeasuresSerializedFormAgainstTheCap() throws Exception {
        PayloadSizeCapGuardrail small = new PayloadSizeCapGuardrail(TEST_ROOT, 10_000);
        small.validate(new CustomerRecord("ok", "fits easily"));
        PayloadSizeCapGuardrail tight = new PayloadSizeCapGuardrail(TEST_ROOT, 10);
        CustomerRecord record = new CustomerRecord("too", "long for a ten character cap");
        GuardrailException e = assertThrows(GuardrailException.class, () -> tight.validate(record));
        int serialized = NucleoJsonSerializer.write(record).length();
        assertTrue(e.getMessage().startsWith("Input of " + serialized + " characters exceeds the cap of 10"),
                "the refusal names the serialized length and the cap: " + e.getMessage());
    }

    @Test
    void piiLogsTheKindsItFoundAtErrorAndNeverTheContent() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PIIDetectionGuardrail.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            PIIDetectionGuardrail guard = new PIIDetectionGuardrail(TEST_ROOT);
            assertThrows(GuardrailException.class,
                    () -> guard.validate(new CustomerRecord("A. Customer", "SSN on file: 123-45-6789")));
            assertEquals(1, appender.list.size(), "one line per refusal");
            assertEquals(ch.qos.logback.classic.Level.ERROR, appender.list.get(0).getLevel());
            String line = appender.list.get(0).getFormattedMessage();
            assertTrue(line.startsWith("PII detected in output: SSN detected"), line);
            assertFalse(line.contains("123-45-6789"), "the content never reaches the log: " + line);
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void agentAllowListWithoutAContextIsACodeErrorNotARefusal() {
        AgentClassAllowListAdmissionGuardrail guard =
                new AgentClassAllowListAdmissionGuardrail(TEST_ROOT, Set.of("some.Agent"));
        UncorrectableRuntimeLLMException error = assertThrows(UncorrectableRuntimeLLMException.class, () -> guard.validate(null));
        assertTrue(error.getMessage().contains("AgentClassAllowListAdmissionGuardrail")
                        && error.getMessage().contains("init was never called"),
                "the enforcer always delivers the context, so a null one names the guard and the missing init: " + error.getMessage());
    }

    @Test
    void principalAllowListFailsClosedWithoutSnapshot() {
        PrincipalAllowListGuardrail guard = new PrincipalAllowListGuardrail(TEST_ROOT, Set.of("alice"));
        assertThrows(GuardrailException.class, () -> guard.validate(new Object()));
    }

    @Test
    void directionsAndTargetTypesAreTheDeclaredOnes() {
        assertEquals(ContentGuardrail.Direction.OUTPUT, new PIIDetectionGuardrail(TEST_ROOT).direction());
        assertEquals(ContentGuardrail.Direction.INPUT, new PayloadSizeCapGuardrail(TEST_ROOT, 1).direction());
        assertEquals(Object.class, new PIIDetectionGuardrail(TEST_ROOT).targetType());
        assertEquals(Void.class, new AgentClassAllowListAdmissionGuardrail(TEST_ROOT, Set.of()).targetType());
        assertEquals(Object.class, new PrincipalAllowListGuardrail(TEST_ROOT, Set.of()).targetType());
        assertEquals(Object.class, new PayloadSizeCapGuardrail(TEST_ROOT, 1).targetType());
    }

    @Test
    void piiDetectsEachPatternAndNamesEveryOneItFound() {
        PIIDetectionGuardrail guard = new PIIDetectionGuardrail(TEST_ROOT);
        GuardrailException card = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("A. Customer", "card 4111111111111111 on file")));
        assertTrue(card.getMessage().contains("Credit card detected"), card.getMessage());
        GuardrailException phone = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("A. Customer", "call (212) 555-0123")));
        assertTrue(phone.getMessage().contains("Phone number detected"), phone.getMessage());
        GuardrailException both = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("A. Customer", "SSN 123-45-6789, mail a.customer@example.com")));
        assertTrue(both.getMessage().contains("SSN detected") && both.getMessage().contains("Email address detected"),
                "every kind found is named in the one refusal: " + both.getMessage());
        assertTrue(both.getMessage().startsWith("PII detected in tool output: "), both.getMessage());
    }

    @Test
    void payloadCapNamesTheGatedToolWhenItHasASnapshot() throws Exception {
        PayloadSizeCapGuardrail guard = new PayloadSizeCapGuardrail(TEST_ROOT, 10);
        GuardrailException outside = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("too", "long for a ten character cap")));
        assertTrue(outside.getMessage().endsWith(" for this tool"), "without a snapshot the gated job is unnamed: " + outside.getMessage());
        guard.setGatedSnapshot(GuardrailContractTest.snapshotOf("alice"));
        GuardrailException gated = assertThrows(GuardrailException.class,
                () -> guard.validate(new CustomerRecord("too", "long for a ten character cap")));
        assertTrue(gated.getMessage().endsWith(" for GatedTool"), "the gated job's class is named: " + gated.getMessage());
        guard.validate(null);
    }

    @Test
    void principalAllowListJudgesTheSnapshotsPrincipal() throws Exception {
        PrincipalAllowListGuardrail guard = new PrincipalAllowListGuardrail(TEST_ROOT, Set.of("alice", "ops-oncall"));
        guard.setGatedSnapshot(GuardrailContractTest.snapshotOf("alice"));
        guard.validate(new Object());
        guard.setGatedSnapshot(GuardrailContractTest.snapshotOf("bob"));
        GuardrailException refusal = assertThrows(GuardrailException.class, () -> guard.validate(new Object()));
        assertTrue(refusal.getMessage().contains("Principal bob is not authorized to invoke GatedTool"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("alice") && refusal.getMessage().contains("ops-oncall"),
                "the refusal names the authorized principals: " + refusal.getMessage());
    }

    @Test
    void agentAllowListJudgesTheCallerClassAndNamesTheTool() throws Exception {
        AgentClassAllowListAdmissionGuardrail guard =
                new AgentClassAllowListAdmissionGuardrail(TEST_ROOT, Set.of("com.example.CuratedAgent"));
        guard.init(new AdmissionContext("alice", "com.example.CuratedAgent", GuardrailContractTest.GatedTool.class));
        guard.validate(null);
        guard.init(new AdmissionContext("alice", "com.example.OtherAgent", GuardrailContractTest.GatedTool.class));
        GuardrailException other = assertThrows(GuardrailException.class, () -> guard.validate(null));
        assertTrue(other.getMessage().contains("Tool GatedTool is not available through com.example.OtherAgent"), other.getMessage());
        assertTrue(other.getMessage().contains("com.example.CuratedAgent"), "the refusal names the allowed agents: " + other.getMessage());
        guard.init(new AdmissionContext("alice", null, GuardrailContractTest.GatedTool.class));
        GuardrailException direct = assertThrows(GuardrailException.class, () -> guard.validate(null));
        assertTrue(direct.getMessage().contains("not available through a direct call"),
                "a caller under the workflow root has no agent class: " + direct.getMessage());
    }
}
