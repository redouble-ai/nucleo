/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.conversation.*;
import ai.redouble.nucleo.harness.errors.http.*;
import ai.redouble.nucleo.harness.errors.retry.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.prompt.*;
import ai.redouble.nucleo.tools.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The promises of the LLM-readable hierarchy itself, as EXCEPTIONS.md states them: the sealed
 * shape with exactly two entry points and two branches under each; every correctable exception
 * answers true and appends the "may be correctable" hint, every uncorrectable one answers false
 * and appends the "not correctable" hint; each concrete type renders the LLM message its javadoc
 * shows; {@code wrapWithContext} keeps the correctable/uncorrectable distinction and names the
 * caller's parameter or service; and {@code unwrapRuntime} carries a checked classification into
 * the runtime branch without losing the message.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-16)
 */
public class ErrorsHierarchyContractTest {

    private static Set<Class<?>> permitted(Class<?> sealedType) {
        return new HashSet<>(Arrays.asList(sealedType.getPermittedSubclasses()));
    }

    /** The membership EXCEPTIONS.md draws, class by class. */
    private static final Set<Class<?>> DOCUMENTED = Set.of(
            LLMReadableCheckedException.class, LLMReadableRuntimeException.class,
            CorrectableLLMException.class, UncorrectableLLMException.class,
            CorrectableRuntimeLLMException.class, UncorrectableRuntimeLLMException.class,
            InvalidInputException.class, Http400Exception.class, Http413Exception.class, Http422Exception.class,
            ResourceNotFoundException.class, Http404Exception.class,
            GuardrailException.class, JsonParseException.class, ResponseValidationException.class,
            ExternalServiceException.class, Http500Exception.class, Http502Exception.class, Http503Exception.class,
            HttpUnmappedStatusException.class, UpstreamThrottleException.class, Http429Exception.class,
            UnauthorizedException.class, Http401Exception.class, Http403Exception.class,
            PermissionDeniedException.class, SystemException.class,
            JobCancelledException.class, JobContext.CancellationException.class, JobTimeoutException.class,
            TokenEstimateExceedsLimitException.class, ContextOverflowException.class, PromptNotFoundException.class,
            QuotaExhaustedException.class, SpendCapExceededException.class, ModelNotFoundException.class,
            ProviderRefusalException.class);

    @Test
    void theDocumentedMembershipIsTheWholeHierarchyOnTheClasspath() throws Exception {
        Set<Class<?>> found = new HashSet<>();
        Set<Class<?>> overriding = new HashSet<>();
        for (Class<?> c : ErrorsClasspath.runtimeClasses()) {
            if (LLMReadableException.class.isAssignableFrom(c) && !c.isInterface()) {
                found.add(c);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("explainToLLM") && m.getParameterCount() == 0) {
                        overriding.add(c);
                    }
                }
            }
        }
        assertEquals(DOCUMENTED, found, "every LLM-readable class on the runtime's classpath is in the diagram, and every diagram entry exists");
        assertEquals(Set.of(LLMReadableCheckedException.class, CorrectableLLMException.class, UncorrectableLLMException.class,
                        CorrectableRuntimeLLMException.class, UncorrectableRuntimeLLMException.class),
                overriding, "the checked entry point restates the default and the four branch classes append the hint; no concrete class overrides explainToLLM");
    }

    @Test
    void aToolDeclaresTheCheckedTypeAndNothingBroader() throws NoSuchMethodException {
        Method execute = Tool.class.getMethod("execute", JobResources.class, JobContext.class);
        assertArrayEquals(new Class<?>[] {LLMReadableCheckedException.class}, execute.getExceptionTypes(),
                "Tool.execute narrows Job.execute's throws Exception to the checked entry point");
    }

    @Test
    void theHierarchyIsSealedToTwoEntryPointsAndTwoBranchesEach() {
        assertEquals(Set.of(LLMReadableCheckedException.class, LLMReadableRuntimeException.class),
                permitted(LLMReadableException.class), "a checked entry point and an unchecked one, nothing else");
        assertEquals(Set.of(CorrectableLLMException.class, UncorrectableLLMException.class),
                permitted(LLMReadableCheckedException.class), "the checked branch splits into correctable and uncorrectable");
        assertEquals(Set.of(CorrectableRuntimeLLMException.class, UncorrectableRuntimeLLMException.class),
                permitted(LLMReadableRuntimeException.class), "and so does the unchecked branch");
    }

    @Test
    void correctableAnswersTrueAndAppendsTheMayBeCorrectableHint() {
        InvalidInputException checked = new InvalidInputException("maxResults", 1000, "Must be between 1 and 100");
        CorrectableRuntimeLLMException runtime = new CorrectableRuntimeLLMException("Parameter 'items' contains a non-integer");
        for (LLMReadableException e : List.of(checked, runtime)) {
            assertTrue(e.isCorrectable(), e.getClass().getSimpleName() + " is the LLM's input to fix");
            assertEquals(e.getLLMMessage() + "\n[This error may be correctable - you can retry with different parameters or try a different approach]",
                    e.explainToLLM(), e.getClass().getSimpleName() + " tells the model it may retry");
        }
    }

    @Test
    void uncorrectableAnswersFalseAndAppendsTheNotCorrectableHint() {
        ExternalServiceException checked = new ExternalServiceException("PubMed API", "Connection timeout after 30 seconds");
        UncorrectableRuntimeLLMException runtime = new UncorrectableRuntimeLLMException("Database connection unavailable");
        for (LLMReadableException e : List.of(checked, runtime)) {
            assertFalse(e.isCorrectable(), e.getClass().getSimpleName() + " cannot be fixed by the model's input");
            assertEquals(e.getLLMMessage() + "\n[This error is not correctable - consider an alternative approach]",
                    e.explainToLLM(), e.getClass().getSimpleName() + " tells the model to take another approach");
        }
    }

    @Test
    void aRuntimeExceptionsMessageIsItsLLMMessage() {
        IllegalStateException cause = new IllegalStateException("pool closed");
        UncorrectableRuntimeLLMException e = new UncorrectableRuntimeLLMException("Database connection unavailable", cause);
        assertEquals("Database connection unavailable", e.getLLMMessage());
        assertEquals(e.getLLMMessage(), e.getMessage(), "the runtime branch carries one message for both audiences");
        assertSame(cause, e.getCause());
    }

    @Test
    void invalidInputNamesTheParameterTheValueAndTheRule() {
        InvalidInputException e = new InvalidInputException("maxResults", 1000, "Must be between 1 and 100");
        assertEquals("Parameter 'maxResults' has invalid value '1000'. Must be between 1 and 100", e.getLLMMessage());
        assertEquals("Validation failed for parameter 'maxResults': Must be between 1 and 100", e.getMessage(),
                "the technical message names the parameter and the rule, not the value");
        assertEquals("maxResults", e.getParameterName());
        assertEquals(1000, e.getInvalidValue());
        assertEquals("Must be between 1 and 100", e.getValidationRule());
    }

    @Test
    void resourceNotFoundNamesTheResourceTypeAndTheIdentifier() {
        ResourceNotFoundException e = new ResourceNotFoundException("PMC article", "PMC1234567");
        assertEquals("PMC article 'PMC1234567' was not found. Try a different identifier or use an alternative tool.", e.getLLMMessage());
        assertEquals("PMC article 'PMC1234567' was not found", e.getMessage());
        assertEquals("PMC article", e.getResourceType());
        assertEquals("PMC1234567", e.getIdentifier());
    }

    @Test
    void theUncorrectableTypesRenderTheirDocumentedMessages() {
        ExternalServiceException external = new ExternalServiceException("PubMed API", "Connection timeout after 30 seconds");
        assertEquals("PubMed API service error: Connection timeout after 30 seconds", external.getLLMMessage());
        assertEquals(external.getLLMMessage(), external.getMessage(), "service failures read the same to both audiences");
        assertEquals("PubMed API", external.getServiceName());
        assertEquals("Connection timeout after 30 seconds", external.getErrorDetails());
        UnauthorizedException unauthorized = new UnauthorizedException("EPO OPS", "API key rejected (HTTP 401)");
        assertEquals("Access denied to EPO OPS: API key rejected (HTTP 401)", unauthorized.getLLMMessage());
        assertEquals("EPO OPS", unauthorized.getServiceName());
        assertEquals("API key rejected (HTTP 401)", unauthorized.getReason());
        PermissionDeniedException denied = new PermissionDeniedException("User deletion requires admin privileges");
        assertEquals("Permission denied: User deletion requires admin privileges", denied.getLLMMessage());
    }

    @Test
    void aSystemErrorNamesTheComponentToTheModelAndTheTechnicalDetailToTheLog() {
        RuntimeException bug = new NullPointerException("tool");
        SystemException e = new SystemException("Tool instantiation", "Failed to create instance of com.foo.MyTool", bug);
        assertEquals("A system error occurred in Tool instantiation. The operation could not be completed.", e.getLLMMessage(),
                "the model learns where, never the stack");
        assertEquals("System error in Tool instantiation: Failed to create instance of com.foo.MyTool", e.getMessage());
        assertEquals("Tool instantiation", e.getComponent());
        assertEquals("Failed to create instance of com.foo.MyTool", e.getTechnicalMessage());
        assertSame(bug, e.getCause());
    }

    @Test
    void aStoppedJobIsUncorrectableAndKeepsWhatStoppedItAsTheCause() {
        IOException casualty = new IOException("Statement closed");
        JobTimeoutException timedOut = new JobTimeoutException(Duration.ofMinutes(5), casualty);
        assertFalse(timedOut.isCorrectable());
        assertSame(casualty, timedOut.getCause());
        assertEquals(Duration.ofMinutes(5), timedOut.getBudget());
        assertTrue(timedOut.getLLMMessage().contains("(PT5M)"), "the budget is named to the model: " + timedOut.getLLMMessage());
        assertFalse(new JobTimeoutException(null, casualty).getLLMMessage().contains("("), "no budget, no parenthesis");
        CancellationException signal = new CancellationException("cancelled");
        JobCancelledException cancelled = new JobCancelledException(signal);
        assertFalse(cancelled.isCorrectable());
        assertSame(signal, cancelled.getCause());
        assertEquals("The operation was cancelled before it completed. The result is unavailable.", cancelled.getLLMMessage());
    }

    @Test
    void aSpendCapRefusalIsAnUncorrectableRuntimeException() {
        SpendCapExceededException e = new SpendCapExceededException("Workflow cap of 5 USD would be crossed");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, e, "no change of input makes the money appear");
        assertEquals("Workflow cap of 5 USD would be crossed", e.getLLMMessage());
    }

    @Test
    void unwrapTypesARawFailureAsASystemErrorCarryingTheRootMessage() {
        LLMReadableCheckedException e = LLMReadableCheckedException.unwrap(
                new ExecutionException("job failed", new IllegalStateException("pool exhausted")));
        SystemException system = assertInstanceOf(SystemException.class, e);
        assertEquals("internal", system.getComponent());
        assertEquals("pool exhausted", system.getTechnicalMessage(), "the root cause's message, not the wrapper's");
        SystemException nameless = assertInstanceOf(SystemException.class, LLMReadableCheckedException.unwrap(new ExecutionException(null, null)));
        assertEquals("ExecutionException", nameless.getTechnicalMessage(), "a chain with no message at all falls back to the class name");
    }

    @Test
    void unwrapThrowsARuntimeLLMReadableFoundInTheChainAsItself() {
        SpendCapExceededException buried = new SpendCapExceededException("cap");
        SpendCapExceededException thrown = assertThrows(SpendCapExceededException.class,
                () -> LLMReadableCheckedException.unwrap(new RuntimeException("wrapper", buried)));
        assertSame(buried, thrown, "the classification travels as the original object");
    }

    @Test
    void unwrapAndWrapWithContextRethrowEveryTransparentRetrySignal() {
        List<RuntimeException> signals = List.of(
                new RateLimitRetryException("429", null, 1, "slow down", Duration.ofSeconds(3), null),
                new OverloadRetryException("529", "anthropic", "overloaded", null),
                new TransientErrorRetryException("500", "openai", "api_error", 500, 1, null),
                new ResponseCorrectionRetryException("m", 1, new JsonParseException("no object", null)),
                new OutputTruncationRetryException("m", 1000, 4000));
        for (RuntimeException signal : signals) {
            RuntimeException wrapped = new RuntimeException("broad catch", new ExecutionException(signal));
            RuntimeException fromUnwrap = assertThrows(RuntimeException.class, () -> LLMReadableCheckedException.unwrap(wrapped));
            assertSame(signal, fromUnwrap, signal.getClass().getSimpleName() + " must reach the dispatcher, never a terminal wrapper");
            RuntimeException fromWrap = assertThrows(RuntimeException.class,
                    () -> LLMReadableCheckedException.wrapWithContext(wrapped, "svc", "p", "v", "op"));
            assertSame(signal, fromWrap, signal.getClass().getSimpleName() + " survives wrapWithContext the same way");
        }
    }

    @Test
    void wrapWithContextKeepsACorrectableFailureCorrectableAndNamesTheCallersParameter() {
        Http400Exception upstream = new Http400Exception("svc", "/compound", "bad cid");
        RuntimeException caught = new RuntimeException(upstream);
        LLMReadableCheckedException e = LLMReadableCheckedException.wrapWithContext(caught, "PubChem", "compoundIdentifier", 42, "CID resolution");
        InvalidInputException invalid = assertInstanceOf(InvalidInputException.class, e);
        assertEquals("compoundIdentifier", invalid.getParameterName(), "the model learns which of ITS inputs to fix");
        assertEquals(42, invalid.getInvalidValue());
        assertEquals("CID resolution: " + upstream.getLLMMessage(), invalid.getValidationRule());
        assertSame(caught, invalid.getCause(), "the throwable that was caught is the cause");
    }

    @Test
    void wrapWithContextKeepsAnUncorrectableFailureUncorrectableAndNamesTheService() {
        Http503Exception upstream = new Http503Exception("svc", "/compound", "down");
        LLMReadableCheckedException e = LLMReadableCheckedException.wrapWithContext(upstream, "PubChem", "cid", 42, "compound fetch");
        ExternalServiceException external = assertInstanceOf(ExternalServiceException.class, e);
        assertEquals("PubChem", external.getServiceName());
        assertEquals("compound fetch: " + upstream.getLLMMessage(), external.getErrorDetails());
        LLMReadableCheckedException raw = LLMReadableCheckedException.wrapWithContext(
                new RuntimeException("outer", new IOException("connection reset")), "PubChem", "cid", 42, "compound fetch");
        assertEquals("compound fetch: connection reset", assertInstanceOf(ExternalServiceException.class, raw).getErrorDetails(),
                "a raw failure becomes a service failure carrying the deepest message");
    }

    @Test
    void unwrapRuntimeCarriesACheckedClassificationIntoTheRuntimeBranch() {
        InvalidInputException correctable = new InvalidInputException("p", "v", "rule");
        RuntimeException wrapper = new RuntimeException(correctable);
        LLMReadableRuntimeException e = LLMReadableRuntimeException.unwrapRuntime(wrapper, "fallback");
        assertInstanceOf(CorrectableRuntimeLLMException.class, e, "correctable stays correctable");
        assertEquals(correctable.getLLMMessage(), e.getLLMMessage());
        assertSame(wrapper, e.getCause());
        assertInstanceOf(UncorrectableRuntimeLLMException.class,
                LLMReadableRuntimeException.unwrapRuntime(new ExternalServiceException("svc", "down"), "fallback"),
                "uncorrectable stays uncorrectable");
        SpendCapExceededException runtime = new SpendCapExceededException("cap");
        assertSame(runtime, LLMReadableRuntimeException.unwrapRuntime(new RuntimeException(runtime), "fallback"),
                "a runtime LLM-readable in the chain is returned as itself");
        LLMReadableRuntimeException fallback = LLMReadableRuntimeException.unwrapRuntime(new IOException("disk"), "Failed to load conversation 7");
        assertInstanceOf(UncorrectableRuntimeLLMException.class, fallback);
        assertEquals("Failed to load conversation 7", fallback.getLLMMessage(), "nothing readable in the chain: the caller's fallback");
    }

    @Test
    void theTypesOutsideTheHierarchyKeepTheirDiagnosticFields() {
        assertInstanceOf(UnsupportedOperationException.class, new NotImplementedException("planned"),
                "a planned surface is refused through the standard type so existing handlers keep working");
        DependencyFailedException dependency = new DependencyFailedException("upstream job failed", "job-17");
        assertEquals("job-17", dependency.getFailedDependencyId());
        JobDeadlockException deadlock = new JobDeadlockException("ParentJob", "p-1", "wf-1", "ChildJob", "c-1", "ParentJob.execute:42");
        assertEquals("ChildJob", deadlock.getCalledJobClass());
        assertEquals("wf-1", deadlock.getCallerWorkflowId());
        assertTrue(deadlock.getMessage().contains("ParentJob") && deadlock.getMessage().contains("ChildJob")
                && deadlock.getMessage().contains("ParentJob.execute:42"), "the message names both jobs and the call site");
        assertInstanceOf(IllegalStateException.class, deadlock);
        for (Object plain : List.of(new NotImplementedException("planned"), dependency, deadlock)) {
            assertFalse(plain instanceof LLMReadable, plain.getClass().getSimpleName() + " is not addressed to the model");
        }
    }
}
