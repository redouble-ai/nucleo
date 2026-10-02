/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

/**
 * Validation guardrail: judges a producer's candidate final answer while the producer
 * is still live and able to act on the verdict.
 * <p>
 * The rung above {@link ContentGuardrail}'s OUTPUT direction. An output content guard
 * runs at the dispatch door after the producing job has returned, so its refusal can
 * only fail the caller. A validation guard runs at the producer's final-answer seat,
 * before the producer releases its conversation: a refusal reaches the producer as
 * correctable feedback in its own context, the candidate is discarded, and the
 * producer tries again. The two are different questions - "may this leave" versus "is
 * this right, and if not, tell the author" - so a producer may declare both, and
 * nothing is evaluated twice.
 * <p>
 * Declared by the producing orchestrator ({@code SingleObjectiveThinker.declareValidationGuardrails()})
 * as constructed instances typed to its answer, consulted once per candidate. What the
 * guard does to reach its verdict is the author's business: a coded field-by-field
 * check, a model call under its own declared resources, or - when the guard declares
 * {@link ScopeAuthority} - a judge thinker it spawns, which inherits the producer's
 * sealed scope through the door like any child.
 * <p>
 * A validation guard's {@link Scoped#scope()}, when it declares one, is the guard's own
 * claim and never derives from its target: the door judges it before the guard runs,
 * and a guard refused at the door has rendered no verdict on the answer - that refusal
 * is a code error of the guard's declaration and fails the producer closed.
 *
 * @param <T> the answer type this guardrail validates
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-01)
 */
public non-sealed interface ValidationGuardrail<T> extends Guardrail<T> {
}
