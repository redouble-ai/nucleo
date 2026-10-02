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
        rethrowRetrySignals(t);
        // Walk the cause chain to find any LLM-readable exception (checked or runtime).
        LLMReadableException llm = findLLMReadable(t);
        if (llm != null) {
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
     * <p>Walks the cause chain looking for an existing LLM-readable exception. If a checked
     * {@link LLMReadableCheckedException} is found, returns it directly. If a runtime
     * {@link LLMReadableRuntimeException} is found, throws it directly (preserving its
     * correctable/uncorrectable classification). Otherwise wraps in a SystemException.</p>
     *
     * <p><strong>Note:</strong> This method may throw instead of returning: a
     * {@link LLMReadableRuntimeException} found in the chain is rethrown as itself, and so is a
     * dispatcher retry signal - every {@link UpstreamRetryException}, and the dispatcher's own
     * {@link ResponseCorrectionRetryException} and {@link OutputTruncationRetryException} - so the
     * dispatcher's backoff machinery sees it rather than a terminal wrapper. All call sites use
     * {@code throw LLMReadableCheckedException.unwrap(e)}, so every path exits with the correct
     * exception type.</p>
     *
     * @param t the throwable to unwrap
     * @return an LLMReadableException - either the original buried one, or a new SystemException
     * @throws LLMReadableRuntimeException if a runtime LLM-readable exception is found in the chain
     */
    public static LLMReadableCheckedException unwrap(Throwable t) {
        rethrowRetrySignals(t);
        LLMReadableException llm = findLLMReadable(t);
        if (llm instanceof LLMReadableCheckedException checked) return checked;
        if (llm instanceof LLMReadableRuntimeException runtime) throw runtime;
        String message = getMostMeaningfulMessage(t);
        return new SystemException("internal", message, t);
    }

    /**
     * Rethrows a dispatcher retry signal buried anywhere in the cause chain. Every
     * {@link UpstreamRetryException} (429 rate limit, 529 overload, plain 5xx fault)
     * is an infrastructure signal, not an error: the dispatcher's backoff-and-retry
     * machinery consumes them without charging the job's retry budget, but only if
     * they reach it, and a broad catch that funnels them through {@link #unwrap} or
     * {@link #wrapWithContext} would otherwise turn throttling the dispatcher knows how
     * to wait out into a terminal failure. Keying on the sealed parent means a newly
     * added signal can never miss this rescue. The dispatcher's two re-run signals of its
     * own, a response correction and an output truncation escalation, are rescued the
     * same way: a one-call tool that catches broadly around its exchange would otherwise
     * turn the model's second chance into a terminal failure.
     */
    private static void rethrowRetrySignals(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof UpstreamRetryException retrySignal) {
                throw retrySignal;
            }
            // the dispatcher's own re-run signals: a correction turn appended to the conversation,
            // an output budget escalated to the ceiling. Wrapped, they would end the job instead
            if (current instanceof ResponseCorrectionRetryException correction) {
                throw correction;
            }
            if (current instanceof OutputTruncationRetryException truncation) {
                throw truncation;
            }
            current = current.getCause();
        }
    }

    /**
     * Walks the cause chain looking for the first {@link LLMReadableException} exception
     * (checked or runtime).
     *
     * @param t the throwable to inspect
     * @return the first LLMReadable in the chain, or null if none found
     */
    private static LLMReadableException findLLMReadable(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof LLMReadableException lr) {
                return lr;
            }
            current = current.getCause();
        }
        return null;
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
