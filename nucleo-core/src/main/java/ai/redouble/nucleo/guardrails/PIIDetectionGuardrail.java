/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.guardrails;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.schema.*;
import org.slf4j.*;

import java.util.regex.*;

/**
 * Reference OUTPUT-direction {@link ContentGuardrail}: detects potential PII in tool
 * results before they reach the conversation - Social Security numbers, credit card
 * numbers, email addresses, phone numbers.
 * <p>
 * Targets {@code Object}: it applies to any result of the tool that declares it.
 * Scanning runs over the result's serialized JSON form, so every field participates -
 * a POJO without a meaningful {@code toString} hides nothing. A null result passes. The
 * refusal names every kind it found in one message, and that summary, never the
 * content, is logged at ERROR.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-03-13)
 */
public class PIIDetectionGuardrail extends AbstractContentGuardrail<Object> {
    private static final Logger log = LoggerFactory.getLogger(PIIDetectionGuardrail.class);
    private static final Pattern SSN_PATTERN = Pattern.compile(
        "\\b(?!000|666|9\\d{2})\\d{3}[-\\s]?(?!00)\\d{2}[-\\s]?(?!0000)\\d{4}\\b"
    );
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
        "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12})\\b"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b"
    );
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "\\b(?:\\+?1[-\\s]?)?\\(?[2-9]\\d{2}\\)?[-\\s]?\\d{3}[-\\s]?\\d{4}\\b"
    );

    public PIIDetectionGuardrail(Identifiable parent) {
        super(parent);
    }

    @Override
    public Direction direction() {
        return Direction.OUTPUT;
    }

    @Override
    public Class<Object> targetType() {
        return Object.class;
    }

    @Override
    public void validate(Object output) throws GuardrailException {
        if (output == null) {
            return;
        }
        String outputText = NucleoJsonSerializer.write(output);
        StringBuilder violations = new StringBuilder();
        if (containsPattern(outputText, SSN_PATTERN)) {
            violations.append("SSN detected; ");
        }
        if (containsPattern(outputText, CREDIT_CARD_PATTERN)) {
            violations.append("Credit card detected; ");
        }
        if (containsPattern(outputText, EMAIL_PATTERN)) {
            violations.append("Email address detected; ");
        }
        if (containsPattern(outputText, PHONE_PATTERN)) {
            violations.append("Phone number detected; ");
        }
        if (!violations.isEmpty()) {
            log.error("PII detected in output: {}", violations);
            throw new GuardrailException("PII detected in tool output: " + violations);
        }
    }

    private boolean containsPattern(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find();
    }
}
