/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.mcp.server;

import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.tools.registry.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * The admission gate for arguments arriving from outside the process.
 *
 * <p>In process, input comes from our own model and the serializer is deliberately lenient:
 * unknown properties are ignored, nulls coerce, "yes" becomes true, because a model that
 * writes sloppy JSON should correct itself rather than fail a workflow. None of that
 * reasoning survives a process boundary. A foreign consumer is not self-correcting, it is
 * untrusted, and silently ignoring half of what it sent is how a caller ends up believing
 * a filter applied when it did not.
 *
 * <p>So the published input schema is the acceptance criterion, enforced here: we tell a
 * consumer exactly what we accept and accept exactly that. Anything else is refused before
 * the tool is constructed, as a correctable {@link InvalidInputException}.
 *
 * <h2>What it refuses</h2>
 * <ul>
 *   <li>arguments that are not a JSON object</li>
 *   <li>any property the schema does not declare</li>
 *   <li>a missing or null required property</li>
 *   <li>a value whose JSON type is not the declared one - no coercion, so {@code "5"} is
 *       not an integer and {@code "yes"} is not a boolean; a null on an optional property
 *       is refused the same way, because null is not the declared type either, and an
 *       omitted property is spelled by omitting it</li>
 *   <li>an integer outside the {@code minimum}/{@code maximum} the schema publishes from
 *       the width of the field it feeds, and a number no double can represent</li>
 *   <li>a string outside a declared {@code enum}, or not the ISO-8601 form its
 *       {@code format} publishes</li>
 *   <li>a string carrying unpaired UTF-16 surrogates or C0 control characters other than
 *       tab, newline and carriage return - neither survives being written to a UTF-8
 *       stream or a text column, so they are refused where they arrive rather than
 *       corrupting something later</li>
 * </ul>
 *
 * <h2>What the schema does not say, the gate does not decide</h2>
 * Every rule here is read from the published document. A property that document publishes
 * without a {@code type} accepts any JSON, and the gate carries it: refusing it would refuse
 * what the contract said was allowed, and typing it here would be this class inventing a
 * rule no consumer was told. The generator types every property it emits, so a served tool
 * reaches that branch only if its schema deliberately leaves a property open.
 *
 * <h2>What it does not check</h2>
 * Every limit here is read from the contract or from a physical width; none is a number
 * chosen here. Size, nesting depth and string length are the decoder's, bounded by
 * {@code StreamReadConstraints} before this class sees a tree, and re-checking them would
 * only add a second, tighter number to keep in step with the first.
 *
 * <h2>What it never says</h2>
 * Every message is composed from THIS process's schema - a declared property name, a
 * declared type, a limit - and never from the payload. An unknown property is reported as a
 * count against the accepted names rather than by the name the caller invented, because
 * that name is the caller's text. The rule holds for the whole boundary: see
 * {@link McpErrorText}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-05)
 */
public final class McpInputGate {
    private static final String TYPE = "type";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String ITEMS = "items";
    private static final String ENUM = "enum";
    private static final String FORMAT = "format";
    private static final String MINIMUM = "minimum";
    private static final String MAXIMUM = "maximum";
    private static final String DATE = "date";
    private static final String DATE_TIME = "date-time";
    private static final String REF = "$ref";
    private static final String DEFS = "$defs";
    private static final String DEFS_POINTER = "#/$defs/";
    private static final String REF_PLAIN = "ref";
    private static final String DEFS_PLAIN = "defs";
    private static final String DEFS_POINTER_PLAIN = "#/defs/";
    private static final String ANY_OF = "anyOf";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    private McpInputGate() {
    }

    /**
     * Admits the arguments of one call, or refuses them.
     *
     * <p>The provider is taken whole rather than its name and its schema separately: those
     * two always come from one provider, and passing them apart would let a caller judge one
     * tool's arguments against another tool's contract with nothing to catch it.
     *
     * @param provider the tool being called, which carries both the contract and the name
     *     every refusal is reported under
     * @param arguments the arguments as received
     * @throws InvalidInputException if anything about the arguments is unexpected
     * @throws SystemException if this process cannot read its own published schema
     */
    public static void admit(ToolProvider provider, JsonNode arguments)
            throws LLMReadableCheckedException {
        String toolName = provider.name();
        JsonNode schema;
        try {
            // The PUBLISHED schema, not the generator's: the boundary removes a field from
            // what it publishes, and a gate judging the generator's tree would accept a
            // property no dialect published.
            schema = McpSchemaPublisher.canonicalInputSchema(provider);
        }
        catch (Exception e) {
            // Our own schema, not the caller's input: a broken one is our bug, and admitting
            // unchecked input because we cannot read our own contract is the wrong direction.
            throw new SystemException("McpInputGate", "input schema of tool " + toolName + " is not readable", e);
        }
        admit(toolName, schema, arguments);
    }

    /**
     * Admits one call's arguments against a named schema.
     *
     * <p>A call judges twice, and both judgements are this one: first against the schema the
     * request's dialect published, so a caller that obeyed the document it fetched is never
     * refused by anything else, and then against the canonical schema, which carries the
     * constraints that dialect's vocabulary could not spell. Both compose their refusal here,
     * so a consumer never meets a judge whose words this process did not write.
     *
     * @param toolName the name every refusal is reported under
     * @param schema the document to judge against, in any dialect's spelling
     * @param arguments the arguments as received
     */
    public static void admit(String toolName, JsonNode schema, JsonNode arguments) throws InvalidInputException {
        if (arguments == null || !arguments.isObject()) {
            throw refuse(toolName, "arguments must be a JSON object");
        }
        checkObject(toolName, schema, schema, arguments, "");
    }

    /**
     * The schema a node stands for. A recursive type is published once under {@code $defs}
     * and referred to from every later occurrence; judging the reference itself would judge
     * nothing, so it is followed to the definition before any value is looked at. A
     * reference this schema cannot resolve is a defect in our own document and throws as one.
     */
    private static JsonNode resolve(JsonNode root, JsonNode schema) {
        if (schema == null) {
            return null;
        }
        // Two spellings of one thing: the specification's, and Gemini's, which writes both
        // keywords without the dollar sign. A dialect that renames them has not changed the
        // graph, so the gate follows either.
        String section = schema.has(REF) ? DEFS : schema.has(REF_PLAIN) ? DEFS_PLAIN : null;
        if (section == null) {
            return schema;
        }
        boolean plain = DEFS_PLAIN.equals(section);
        String ref = schema.get(plain ? REF_PLAIN : REF).asText();
        String pointer = plain ? DEFS_POINTER_PLAIN : DEFS_POINTER;
        if (!ref.startsWith(pointer)) {
            throw new IllegalStateException("Published schema carries a reference outside its own definitions: " + ref);
        }
        JsonNode target = root.path(section).get(ref.substring(pointer.length()));
        if (target == null) {
            throw new IllegalStateException("Published schema carries a dangling reference: " + ref);
        }
        return target;
    }

    private static void checkObject(String toolName, JsonNode root, JsonNode schema, JsonNode value, String path)
            throws InvalidInputException {
        JsonNode properties = schema.get(PROPERTIES);
        Set<String> declared = new LinkedHashSet<>();
        if (properties != null && properties.isObject()) {
            properties.fieldNames().forEachRemaining(declared::add);
        }
        // Undeclared properties are refused where the document says the object is closed, and
        // nowhere else. That is the JSON Schema rule, and following it is what keeps a
        // judgement honest to the schema it was handed: the canonical form closes every
        // object, so nothing changes there, while a dialect that cannot spell closure, or
        // that stands a broken cycle up as an object with no properties, is not read as
        // forbidding everything. What such a path leaves open, the canonical pass closes.
        JsonNode closed = schema.get(ADDITIONAL_PROPERTIES);
        if (closed != null && closed.isBoolean() && !closed.asBoolean()) {
            int unexpected = 0;
            Iterator<String> present = value.fieldNames();
            while (present.hasNext()) {
                if (!declared.contains(present.next())) {
                    unexpected++;
                }
            }
            if (unexpected > 0) {
                throw refuse(toolName, at(path) + "carries " + unexpected + " parameter(s) that are not declared. Accepted here: "
                        + (declared.isEmpty() ? "none" : String.join(", ", declared)));
            }
        }
        // A map: the keys are the caller's and every value must be what the schema states for
        // all of them. Judged under the entry's own key, so the refusal names the entry.
        else if (closed != null && closed.isObject()) {
            Iterator<String> present = value.fieldNames();
            while (present.hasNext()) {
                String key = present.next();
                if (!declared.contains(key)) {
                    checkValue(toolName, root, closed, value.get(key), path.isEmpty() ? key : path + "." + key);
                }
            }
        }
        JsonNode required = schema.get(REQUIRED);
        if (required != null && required.isArray()) {
            for (JsonNode name : required) {
                // Required means the key is present. Whether the value it carries is
                // acceptable belongs to the type check below, which is the only place that
                // knows whether this schema admits a null there: the strict spellings mark
                // every property required and let an optional one be its type or null, so a
                // rule that read a present null as an absent key would refuse the very
                // documents those paths ask their callers to send.
                if (!value.has(name.asText())) {
                    throw refuse(toolName, at(path) + "is missing the required parameter '" + name.asText() + "'");
                }
            }
        }
        if (properties == null || !properties.isObject()) {
            return;
        }
        for (String name : declared) {
            JsonNode supplied = value.get(name);
            // A present null is a value, and never the declared type: "count": null is no
            // more an integer than "count": "7" is. Letting it through would hand the
            // parser a null to write over whatever the field held.
            if (supplied != null) {
                checkValue(toolName, root, properties.get(name), supplied, path.isEmpty() ? name : path + "." + name);
            }
        }
    }

    private static void checkValue(String toolName, JsonNode root, JsonNode declaredSchema, JsonNode value, String path)
            throws InvalidInputException {
        JsonNode fieldSchema = resolve(root, declaredSchema);
        if (fieldSchema != null && fieldSchema.get(ANY_OF) instanceof ArrayNode alternatives) {
            checkAlternatives(toolName, root, alternatives, value, path);
            return;
        }
        String declared = fieldSchema != null && fieldSchema.get(TYPE) != null ? fieldSchema.get(TYPE).asText() : null;
        if (declared == null) {
            // The gate judges what the schema declares. A property published without a type
            // accepts any JSON, and saying so is the published contract; inventing a type
            // here would refuse what the document said was allowed. The generator types
            // every property it emits, so this is reachable only for a schema that
            // deliberately leaves one open.
            return;
        }
        switch (declared) {
            case "string" -> {
                requireType(toolName, path, value.isTextual(), "a string");
                checkString(toolName, path, fieldSchema, value.asText());
            }
            case "integer" -> {
                requireType(toolName, path, value.isIntegralNumber(), "an integer");
                checkBounds(toolName, path, fieldSchema, value);
            }
            case "number" -> {
                requireType(toolName, path, value.isNumber(), "a number");
                // A magnitude no double can hold arrives as an infinity and would be carried
                // as one; a caller that meant a number has not sent one.
                if (!value.isBigDecimal() && !Double.isFinite(value.asDouble())) {
                    throw refuse(toolName, "'" + path + "' must be a finite number");
                }
            }
            case "boolean" -> requireType(toolName, path, value.isBoolean(), "a boolean");
            // The strict spellings write an optional property as its type or this, so a value
            // reaching here has to actually be null; without the case it would fall through
            // the default and every alternative would accept everything.
            case "null" -> requireType(toolName, path, value.isNull(), "null");
            case "array" -> {
                requireType(toolName, path, value.isArray(), "an array");
                JsonNode items = fieldSchema.get(ITEMS);
                if (items != null) {
                    for (JsonNode element : value) {
                        checkValue(toolName, root, items, element, path + "[]");
                    }
                }
            }
            case "object" -> {
                requireType(toolName, path, value.isObject(), "an object");
                checkObject(toolName, root, fieldSchema, value, path);
            }
            default -> {
            }
        }
    }

    /**
     * A value against a list of alternatives: it satisfies the property if it satisfies any
     * one of them. This is how the strict spellings write an optional property, as its own
     * type or null, so a caller of such a path spells an omission as an explicit null and is
     * admitted for it. The refusal names the alternatives, which are ours, and never the
     * value, which is the caller's.
     */
    private static void checkAlternatives(String toolName, JsonNode root, ArrayNode alternatives, JsonNode value, String path)
            throws InvalidInputException {
        List<String> named = new ArrayList<>();
        for (JsonNode alternative : alternatives) {
            try {
                checkValue(toolName, root, alternative, value, path);
                return;
            }
            catch (InvalidInputException refused) {
                JsonNode resolved = resolve(root, alternative);
                JsonNode type = resolved == null ? null : resolved.get(TYPE);
                named.add(type == null ? "the declared shape" : type.asText());
            }
        }
        throw refuse(toolName, "'" + path + "' must be " + String.join(" or ", named));
    }

    private static void checkString(String toolName, String path, JsonNode fieldSchema, String text)
            throws InvalidInputException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                throw refuse(toolName, "'" + path + "' contains a control character");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw refuse(toolName, "'" + path + "' is not valid text: unpaired surrogate");
                }
                i++;
            }
            else if (Character.isLowSurrogate(c)) {
                throw refuse(toolName, "'" + path + "' is not valid text: unpaired surrogate");
            }
        }
        JsonNode format = fieldSchema.get(FORMAT);
        if (format != null) {
            checkFormat(toolName, path, format.asText(), text);
        }
        JsonNode allowed = fieldSchema.get(ENUM);
        if (allowed != null && allowed.isArray()) {
            for (JsonNode option : allowed) {
                if (option.asText().equals(text)) {
                    return;
                }
            }
            List<String> options = new ArrayList<>();
            allowed.forEach(option -> options.add(option.asText()));
            throw refuse(toolName, "'" + path + "' must be one of: " + String.join(", ", options));
        }
    }

    /**
     * A temporal string must be the ISO-8601 form its schema publishes. Enforced here rather
     * than left to the deserializer below, which is deliberately forgiving for our own model
     * and would read an epoch-seconds string or a local format as a date - a coercion no
     * consumer was told about and none should get.
     */
    private static void checkFormat(String toolName, String path, String format, String text)
            throws InvalidInputException {
        try {
            if (DATE.equals(format)) {
                LocalDate.parse(text);
                return;
            }
            if (DATE_TIME.equals(format)) {
                checkDateTime(text);
            }
        }
        catch (DateTimeParseException e) {
            // The parser quotes the text it could not read; only the expected shape leaves.
            throw refuse(toolName, "'" + path + "' must be an ISO-8601 " + format
                    + (DATE.equals(format) ? " (YYYY-MM-DD)" : " (YYYY-MM-DDThh:mm:ss)"));
        }
    }

    /** A date-time in either ISO shape, offset or local; the parse is the check, its value is not needed. */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static void checkDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
        }
        catch (DateTimeParseException e) {
            LocalDateTime.parse(text);
        }
    }

    /**
     * An integral value must fit the width its schema publishes. Without this the gate would
     * pass 2^64 as "an integer" and the field it was meant for would overflow or throw a
     * layer below, where the complaint quotes the value.
     */
    private static void checkBounds(String toolName, String path, JsonNode fieldSchema, JsonNode value)
            throws InvalidInputException {
        JsonNode minimum = fieldSchema.get(MINIMUM);
        JsonNode maximum = fieldSchema.get(MAXIMUM);
        if (minimum == null || maximum == null) {
            return;
        }
        if (!value.canConvertToLong() || value.asLong() < minimum.asLong() || value.asLong() > maximum.asLong()) {
            throw refuse(toolName, "'" + path + "' must be between " + minimum.asLong() + " and " + maximum.asLong());
        }
    }

    private static void requireType(String toolName, String path, boolean ok, String expected)
            throws InvalidInputException {
        if (!ok) {
            throw refuse(toolName, "'" + path + "' must be " + expected);
        }
    }

    private static String at(String path) {
        return path.isEmpty() ? "the input " : "'" + path + "' ";
    }

    /**
     * The one refusal shape. The parameter is the tool, never a value, and the rule is built
     * from this process's own schema, so nothing a caller sent can travel back out.
     */
    private static InvalidInputException refuse(String toolName, String rule) {
        return new InvalidInputException(toolName, "arguments", rule);
    }
}
