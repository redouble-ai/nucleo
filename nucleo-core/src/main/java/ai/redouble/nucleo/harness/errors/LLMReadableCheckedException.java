/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.errors;

import ai.redouble.nucleo.guardrails.*;
import ai.redouble.nucleo.harness.errors.retry.*;

/**
 * Base class for exceptions that can be communicated to LLMs in an actionable, understandable form.
 *
 * <p>This is the mandatory throws type for all LLM-facing code. {@link ai.redouble.nucleo.tools.Tool#execute}
 * declares {@code throws LLMReadableCheckedException}, which means every tool, thinker, and doer must
 * express errors through this hierarchy. Raw exceptions ({@code IOException}, {@code SQLException})
 * cannot escape - they must be wrapped via {@link #unwrap(Throwable)} or
 * {@link #wrapWithContext(Throwable, String, String, Object, String)}.</p>
 *
 * <p><strong>The two branches define LLM behavior:</strong></p>
 * <ul>
 *   <li><strong>{@link CorrectableLLMException}</strong> - The LLM's input was wrong.
 *       It may fix its parameters and <em>call the same tool again</em>.</li>
 *   <li><strong>{@link UncorrectableLLMException}</strong> - The tool itself cannot work
 *       right now, regardless of input. The LLM should <em>not retry this tool</em> - instead
 *       it should try a different tool, take an alternative approach, or explain the limitation
 *       to the user.</li>
 * </ul>
 *
 * <p>Subclasses should provide clear, actionable error messages that:</p>
 * <ul>
 *   <li>Explain what went wrong in plain language</li>
 *   <li>Indicate which parameter or input caused the problem</li>
 *   <li>Suggest how to fix the issue (valid ranges, formats, etc.)</li>
 *   <li>Avoid technical jargon that LLMs might not understand in context</li>
 * </ul>
 *
 * <p><strong>Design Philosophy:</strong></p>
 * <ul>
 *   <li>Full exception hierarchy (stack traces, causes) preserved for developer debugging</li>
 *   <li>Clean, LLM-friendly messages extracted via {@link #getLLMMessage()}</li>
 *   <li>Formatted output for conversation via {@link #explainToLLM()}</li>
 *   <li>No automatic retries - LLM decides all next steps based on error information</li>
 * </ul>
 *
 * <p><strong>IMPORTANT: Do NOT create service-specific exception hierarchies.</strong></p>
 * <p>NEVER create classes like {@code FooException extends LLMReadableException} for a service.
 * NEVER create {@code FooAuthException}, {@code FooInvalidInputException}, {@code FooServerException}, etc.
 * The framework already provides the right concrete types via {@link CorrectableLLMException} and
 * {@link UncorrectableLLMException}. Every external service should map its HTTP error codes directly
 * to the existing framework types:</p>
 * <ul>
 *   <li>HTTP 400/422 -&gt; {@link InvalidInputException}</li>
 *   <li>HTTP 401/403 -&gt; {@link UnauthorizedException}</li>
 *   <li>HTTP 404 (fetch-by-ID) -&gt; {@link ResourceNotFoundException}</li>
 *   <li>HTTP 413 -&gt; {@link InvalidInputException} (file too large = correctable)</li>
 *   <li>HTTP 429 -&gt; {@link ai.redouble.nucleo.harness.errors.http.Http429Exception}, an
 *       {@link ai.redouble.nucleo.harness.errors.http.UpstreamThrottleException} (rate limit)</li>
 *   <li>HTTP 500+ and any other error status -&gt; {@link ExternalServiceException} (server error)</li>
 *   <li>Network errors -&gt; {@link ExternalServiceException} (connection/timeout)</li>
 * </ul>
 * <p>{@link ai.redouble.nucleo.harness.errors.http.HttpExceptions} performs that mapping for
 * every status; a client never spells it out.</p>
 *
 * <p><strong>The hierarchy:</strong></p>
 * <pre>{@code
 * LLMReadable (marker interface)
 *   +-- LLMReadableException (sealed, extends LLMReadable)
 *         +-- LLMReadableCheckedException (sealed, checked)
 *         |     +-- CorrectableLLMException (non-sealed abstract, isCorrectable=true)
 *         |     |     +-- InvalidInputException, ResourceNotFoundException, GuardrailException, ...
 *         |     +-- UncorrectableLLMException (non-sealed abstract, isCorrectable=false)
 *         |           +-- ExternalServiceException, UnauthorizedException, SystemException, ...
 *         +-- LLMReadableRuntimeException (sealed, unchecked)
 *               +-- CorrectableRuntimeLLMException (non-sealed concrete, isCorrectable=true)
 *               +-- UncorrectableRuntimeLLMException (non-sealed concrete, isCorrectable=false)
 * }</pre>
 *
 * @see LLMReadableException
 * @see LLMReadableRuntimeException
 * @see CorrectableLLMException
 * @see UncorrectableLLMException
 * @see GuardrailException
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-29)
 */
public sealed abstract class LLMReadableCheckedException extends Exception implements LLMReadableException
        permits CorrectableLLMException, UncorrectableLLMException {

    /**
     * Constructs a new LLM-readable exception with the specified detail message.
     *
     * @param message the detail message (for logging and debugging)
     */
    public LLMReadableCheckedException(String message) {
        super(message);
    }

    /**
     * Constructs a new LLM-readable exception with the specified detail message and cause.
     *
     * <p>The cause is preserved for developer debugging while the LLM message is extracted
     * from the subclass implementation.</p>
     *
     * @param message the detail message (for logging and debugging)
     * @param cause   the cause (which is saved for later retrieval by {@link #getCause()})
     */
    public LLMReadableCheckedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns a clean, actionable error message suitable for LLM understanding.
     *
     * <p>This message should:</p>
     * <ul>
     *   <li>Explain what went wrong in plain language</li>
     *   <li>Be specific about which parameter or input caused the problem</li>
     *   <li>Suggest how to fix the issue when possible</li>
     *   <li>Avoid stack traces, exception types, or code references</li>
     * </ul>
     *
     * <p><strong>Good examples:</strong></p>
     * <ul>
     *   <li>"Maximum result limit is 100. Please reduce maxResults parameter to 100 or less."</li>
     *   <li>"Parameter 'dateFrom' has invalid format '2024-13-01'. Use ISO 8601 format: YYYY-MM-DD."</li>
     *   <li>"Permission denied to access system clock. This tool requires elevated privileges."</li>
     * </ul>
     *
     * <p><strong>Bad examples:</strong></p>
     * <ul>
     *   <li>"NullPointerException at line 247"</li>
     *   <li>"Error code -1"</li>
     *   <li>"Invalid input" (not specific enough)</li>
     * </ul>
     *
     * @return clean, actionable error message for LLM
     */
    public abstract String getLLMMessage();

    /**
     * Returns a fully formatted string to add to the LLM conversation context.
     *
     * <p>This declaration restates the interface default: just the LLM message. The four branch
     * classes, {@link CorrectableLLMException}, {@link UncorrectableLLMException},
     * {@link CorrectableRuntimeLLMException} and {@link UncorrectableRuntimeLLMException},
     * override it to append their correctability hint, and no concrete class overrides it
     * again, so every exception tells the model whether to retry in the same words.</p>
     *
     * @return formatted string for conversation context
     */
    public String explainToLLM() {
        return getLLMMessage();
    }

    /**
     * Indicates whether the LLM may retry the same tool with different input.
     *
     * <p><strong>true (correctable):</strong> The LLM's input was wrong. It may fix its
     * parameters and call the same tool again, or take a different approach if correction
     * seems unlikely to help. Examples: invalid query syntax, missing required field,
     * malformed identifier, ID that doesn't exist.</p>
     *
     * <p><strong>false (uncorrectable):</strong> The tool itself cannot work right now,
     * regardless of what the LLM sends. No amount of parameter tweaking will help. The LLM
     * should not retry this tool - instead it should try a different tool, take an alternative
     * approach, or explain the limitation to the user. Examples: API down, auth failure,
     * rate limit, system error.</p>
     *
     * @return true if the LLM should retry the same tool with corrected input,
     *         false if the LLM should abandon this tool and try something else
     */
    public abstract boolean isCorrectable();

    /**
     * Wraps a throwable with semantic context, preserving the correctable/uncorrectable distinction.
     *
     * <p>Use this in multi-step tools where the LLM needs to know which step failed and why.
     * Catches the throwable and call this method to produce a properly typed exception:</p>
     * <ul>
     *   <li>{@link CorrectableLLMException} (e.g., HTTP 400) becomes {@link InvalidInputException}
     *       with the caller's parameter name, so the LLM knows which input to fix</li>
     *   <li>{@link UncorrectableLLMException} (e.g., HTTP 500, auth failure) becomes
     *       {@link ExternalServiceException} with the operation context</li>
     *   <li>Any other throwable becomes {@link ExternalServiceException}</li>
     * </ul>
     *
     * <p>A live retry signal in the chain is rethrown instead, as {@link #unwrap(Throwable)}
     * does and by the same rule.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * try {
     *     response = apiUtil.get(path);
     * } catch (Throwable t) {
     *     throw LLMReadableCheckedException.wrapWithContext(t,
     *         "PubChem", "compoundIdentifier", identifier,
     *         "CID resolution for " + idType);
     * }
     * }</pre>
     *
     * @param t the original throwable
     * @param serviceName the external service name (e.g., "PubChem", "ChEMBL")
     * @param paramName the LLM-facing parameter that triggered this operation
     * @param paramValue the value the LLM provided for that parameter
     * @param operation description of what was being attempted
     * @return an LLMReadableException with context, ready to throw
     */
    public static LLMReadableCheckedException wrapWithContext(Throwable t, String serviceName,
                                                       String paramName, Object paramValue,
                                                       String operation) {
        Throwable decisive = firstDecisive(t);
        if (isRetrySignal(decisive)) {
            throw (RuntimeException) decisive;
        }
        if (decisive instanceof LLMReadableException llm) {
            if (llm.isCorrectable()) {
                return new InvalidInputException(paramName, paramValue,
                    operation + ": " + llm.getLLMMessage(), t);
            }
            return new ExternalServiceException(serviceName,
                operation + ": " + llm.getLLMMessage(), t);
        }

        // No LLM-readable exception anywhere in the chain - use the deepest non-null message
        String message = getMostMeaningfulMessage(t);
        return new ExternalServiceException(serviceName,
            operation + ": " + message, t);
    }

    /**
     * Unwraps a throwable to find a buried {@link LLMReadableException} exception, or wraps it
     * in a {@link SystemException} if none is found.
     *
     * <p>Use this when code must only throw {@code LLMReadableCheckedException} but calls methods
     * that throw broader exceptions (e.g., {@link java.util.concurrent.ExecutionException}
     * from {@code JobHandle.get()}, or {@link InterruptedException}).</p>
     *
     * <p>Walks the cause chain to the first exception that decides the outcome: a dispatcher
     * retry signal or an LLM-readable exception, whichever comes first. A checked
     * {@link LLMReadableCheckedException} is returned. A runtime
     * {@link LLMReadableRuntimeException} is thrown as itself, keeping its
     * correctable/uncorrectable classification. A chain with neither becomes a
     * {@link SystemException}.</p>
     *
     * <p><strong>Retry signals.</strong> A dispatcher retry signal - every
     * {@link UpstreamRetryException}, and the dispatcher's own
     * {@link ResponseCorrectionRetryException} and {@link OutputTruncationRetryException} - is
     * rethrown as itself when nothing LLM-readable lies above it in the chain, so a broad catch
     * around a job's own model exchange hands the signal to that job's dispatcher loop and
     * never turns a wait the dispatcher knows how to do into a failure. A signal that lies
     * beneath an LLM-readable exception is not rethrown: whoever built that exception held the
     * signal and ruled on it. That is how a child job's failure arrives, its retries spent, its
     * time budget or its cancellation reached, and it travels up as the failure it is, with
     * the signal still readable in its cause chain. The job that awaits the child runs once.</p>
     *
     * <p>All call sites use {@code throw LLMReadableCheckedException.unwrap(e)}, so every path
     * exits with the correct exception type.</p>
     *
     * @param t the throwable to unwrap
     * @return an LLMReadableException - either the original buried one, or a new SystemException
     * @throws LLMReadableRuntimeException if a runtime LLM-readable exception is found in the chain
     */
    public static LLMReadableCheckedException unwrap(Throwable t) {
        Throwable decisive = firstDecisive(t);
        if (isRetrySignal(decisive)) {
            throw (RuntimeException) decisive;
        }
        if (decisive instanceof LLMReadableCheckedException checked) {
            return checked;
        }
        if (decisive instanceof LLMReadableRuntimeException runtime) {
            throw runtime;
        }
        String message = getMostMeaningfulMessage(t);
        return new SystemException("internal", message, t);
    }

    /**
     * The first exception of a cause chain that decides what a broad catch does with it: a
     * dispatcher retry signal, or an LLM-readable exception. One walk serves {@link #unwrap}
     * and {@link #wrapWithContext}, so the two can never disagree about where a chain's
     * verdict lies.
     *
     * <p>The order of the chain is the rule. A signal met first is live: it was thrown in this
     * job's own body, under nothing but raw wrappers ({@code ExecutionException},
     * {@code IOException}), and the dispatcher loop of this job is the one that waits it out. An
     * LLM-readable exception met first is a verdict, and the walk ends there: a signal beneath
     * it was already consumed by the code that built the verdict, a dispatcher loop whose budget
     * it spent or a client that refused to retry it, and rescuing it would run this job again
     * for a failure that was already settled below.</p>
     *
     * @param t the throwable a broad catch caught
     * @return the first retry signal or LLM-readable exception in the chain, null when it holds neither
     */
    private static Throwable firstDecisive(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof LLMReadableException || isRetrySignal(current)) {
                return current;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Whether a throwable is one of the signals the dispatcher answers by running the job
     * again: any {@link UpstreamRetryException} (keyed on the sealed parent, so a signal added
     * to it is covered), a response correction, an output truncation escalation. All three are
     * runtime exceptions and none is LLM-readable.
     */
    private static boolean isRetrySignal(Throwable t) {
        return t instanceof UpstreamRetryException
                || t instanceof ResponseCorrectionRetryException
                || t instanceof OutputTruncationRetryException;
    }

    /**
     * Returns the most meaningful message from a throwable chain.
     * Wrapper exceptions like {@link java.util.concurrent.ExecutionException} often have
     * generic messages; the root cause usually has the actionable detail.
     *
     * @param t the throwable to extract a message from
     * @return the most meaningful message found, or the class name as last resort
     */
    private static String getMostMeaningfulMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }

        // Prefer the root cause message, fall back to the top-level message
        String rootMessage = current.getMessage();
        if (rootMessage != null && !rootMessage.isBlank()) {
            return rootMessage;
        }
        String topMessage = t.getMessage();
        if (topMessage != null && !topMessage.isBlank()) {
            return topMessage;
        }
        return current.getClass().getSimpleName();
    }
}
