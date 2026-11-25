/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.schema;

import ai.redouble.nucleo.harness.artifacts.*;
import ai.redouble.nucleo.util.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.json.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.*;
import com.fasterxml.jackson.databind.exc.*;
import com.fasterxml.jackson.databind.introspect.*;
import com.fasterxml.jackson.databind.json.*;
import com.fasterxml.jackson.databind.module.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.ser.*;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.fasterxml.jackson.datatype.jsr310.*;
import org.slf4j.*;

import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Central JSON serialization for the Nucleo framework.
 *
 * <p>All JSON serialization and deserialization MUST go through this class. Never create an
 * ObjectMapper directly: the one mapper here is what keeps every reader and writer in the
 * runtime on the same naming, the same leniencies and the same coercions.
 *
 * <p>Serialization modes:
 * <ul>
 *   <li>{@link #write} / {@link #writeCompact} - Full data, no transformations.
 *       Pretty-printed vs compact.</li>
 *   <li>{@link #writeSummarized} / {@link #writeSummarizedCompact} - an artifact is written
 *       with its fields, each long {@code @LLMSummarizable} field as its summary.</li>
 *   <li>{@link #writeSummarizedWithRefs} - every artifact is replaced with
 *       {@code {"@ref": "..."}} and registered in the provided registry.
 *       Use right before passing content to the model.</li>
 *   <li>{@link #modelFacingFields} - one artifact with every field whole, as a model may
 *       read it back: the form the artifact tools serve.</li>
 * </ul>
 *
 * <p>There is one way a summary is made, and no write is handed a summarizer. What a
 * field's summary is follows from its annotation: the fixed phrase of
 * {@link LLMSummarizable#staticSummary()}; else the class named by
 * {@link LLMSummarizable#summarizer()}; else a cut at a word boundary when
 * {@link LLMSummarizable#llmSafe()} is false or the text is longer than
 * {@value #MODEL_SUMMARY_MAX_CHARS} characters; else a model's summary. It is made once and
 * kept in the artifact's {@link ArtifactSummaries}, and a kept summary is what every later
 * write shows. A model is asked only by {@link #makeSummaries}; any other write that meets a
 * field whose summary needs a model cuts the text for that one write and keeps nothing. A
 * summarization that fails, and a named class that throws, are logged and keep nothing: the
 * write is cut and the next {@link #makeSummaries} asks again.
 *
 * <p>A summary stands for a value the model can read whole through the artifact that holds
 * it, so a field is summarized only when the class that declares it is an {@link Artifact}.
 * The annotation anywhere else is refused when the code is compiled and when the dispatcher
 * starts; a write that still meets one writes the field whole.
 *
 * <p>The compact variants back observability storage (the job and call records'
 * JSON columns): those bytes are kept forever in never-purged audit tables, and every
 * reader parses before rendering, so formatting whitespace there is pure cost.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-03)
 */
public class NucleoJsonSerializer {
    private static final Logger log = LoggerFactory.getLogger(NucleoJsonSerializer.class);
    /** The key under which an artifact is shown to a model in place of its content: {@code {"@ref": "..."}}. */
    public static final String ARTIFACT_REF_AS_SHOWN = "@ref";
    /** The serialized name of {@link ArtifactResponse}'s list of references. */
    static final String SELECTED_REFS_PROPERTY = "artifact_refs";
    static final String ATTR_MODE = "nucleo.mode";
    /** The summarizer that asks a model, present only in a write of {@link #makeSummaries}. */
    static final String ATTR_MODEL = "nucleo.model";
    static final String ATTR_REGISTRY = "nucleo.registry";
    /** The one artifact a {@link #MODE_FIELDS} write shows with its fields. */
    static final String ATTR_ROOT = "nucleo.root";
    static final String MODE_LLM_REF = "LLM_REF";
    static final String MODE_SUMMARIZED = "SUMMARIZED";
    /** One artifact with every field whole; every artifact inside it is a reference. */
    static final String MODE_FIELDS = "FIELDS";
    /** An object kept beside a registry: everything whole, every artifact a reference into the registry. */
    static final String MODE_STORED = "STORED";
    /** A text longer than this is cut, never handed to a model to summarize. */
    public static final int MODEL_SUMMARY_MAX_CHARS = 200_000;
    private static final TruncatingSummarizer CUT = new TruncatingSummarizer();

    // Tracks which fields have already been warned about missing hints (once per field per JVM)
    private static final Set<String> WARNED_MISSING_HINTS = ConcurrentHashMap.newKeySet();
    private static final ObjectMapper MAPPER = createMapper();
    private static final ObjectWriter PRETTY_WRITER = MAPPER.writerWithDefaultPrettyPrinter();

    // JDK scalar/temporal types that serialize to a single JSON value. Recursing into their
    // fields would produce garbage schemas, so they're treated as leaves.
    private static final Set<Class<?>> LEAF_TYPES = Set.of(
        String.class, CharSequence.class, Boolean.class, Character.class,
        Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
        BigDecimal.class, BigInteger.class,
        UUID.class, Date.class, Locale.class, Currency.class,
        Instant.class, LocalDate.class, LocalDateTime.class,
        ZonedDateTime.class, OffsetDateTime.class, OffsetTime.class, LocalTime.class,
        Duration.class, Period.class, Year.class, YearMonth.class, MonthDay.class);

    // ========================= Public API =========================

    // --- Standard serialization ---

    /**
     * Serialize to pretty-printed JSON. Full data, no transformations.
     * {@code @LLMSummarizable} annotations are ignored.
     *
     * @param obj the object to serialize
     * @return pretty-printed JSON string
     * @throws RuntimeException if serialization fails (programming error)
     */
    public static String write(Object obj) {
        try {
            return PRETTY_WRITER.writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }

    /**
     * Serialize to compact JSON (no pretty-print). The standard form for
     * machine-read storage such as the observability columns; prefer
     * {@link #write} where a human reads the raw string.
     *
     * @param obj the object to serialize
     * @return compact JSON string
     * @throws RuntimeException if serialization fails (programming error)
     */
    public static String writeCompact(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }

    // --- LLM-aware serialization ---

    /**
     * Serialize an object as a model is shown it: an artifact with its fields, each long
     * {@code @LLMSummarizable} field as its summary, and no {@code @LLMContextIgnore} field.
     * A summary that is kept is shown; one that needs no model is made and kept; one that
     * needs a model is a cut of the text for this write, with nothing kept.
     *
     * @param obj the object to serialize
     * @return pretty-printed JSON with long fields summarized
     */
    public static String writeSummarized(Object obj) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter()
                .withAttribute(ATTR_MODE, MODE_SUMMARIZED)
                .writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }

    /**
     * Makes and keeps every summary an artifact is shown with, asking the given summarizer
     * for the ones that need a model. This is the one place a model is asked: the artifact
     * is written as {@link #writeSummarized} writes it, the artifacts inside it included,
     * and the text is discarded.
     *
     * <p>A summarization that fails keeps nothing and is logged; the next call asks again.
     * When the calling thread is interrupted, no further summary is asked for and the
     * thread's interrupt flag is left set for the caller to act on.
     *
     * @param artifact the artifact whose summaries are made
     * @param model asks a model for the summary of one text; it throws when it cannot
     */
    public static void makeSummaries(Artifact artifact, Summarizer model) {
        try {
            MAPPER.writer()
                .withAttribute(ATTR_MODE, MODE_SUMMARIZED)
                .withAttribute(ATTR_MODEL, model)
                .writeValue(OutputStream.nullOutputStream(), artifact);
        }
        catch (JsonProcessingException e) {
            throw serializationError(artifact, e);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serialize an object that is stored beside the registry holding its artifacts: every
     * field is written, whole, and every artifact, the object itself included, is replaced
     * with {@code {"@ref": "..."}} and registered in the registry. Nothing is summarized and
     * nothing is left out, so the object read back and given the registry's artifacts
     * ({@link ArtifactRegistry#held}) is the object that was written.
     *
     * @param obj the object to serialize
     * @param registry the registry the artifacts are stored in
     * @return pretty-printed JSON with artifacts as refs
     */
    public static String writeStoredWithRefs(Object obj, ArtifactRegistry registry) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter()
                .withAttribute(ATTR_MODE, MODE_STORED)
                .withAttribute(ATTR_REGISTRY, registry)
                .writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }

    /**
     * One artifact as a model may read it back in full: every field whole, no
     * {@code @LLMContextIgnore} field, and every artifact inside it as
     * {@code {"@ref": "..."}}. No reference is minted and nothing is registered: an artifact
     * inside that carries no reference fails the write with {@link IllegalStateException}.
     *
     * @param artifact the artifact to read
     * @return its fields by their serialized names
     */
    public static ObjectNode modelFacingFields(Artifact artifact) {
        try {
            TokenBuffer buffer = new TokenBuffer(MAPPER, false);
            MAPPER.writer()
                .withAttribute(ATTR_MODE, MODE_FIELDS)
                .withAttribute(ATTR_ROOT, artifact)
                .writeValue(buffer, artifact);
            return (ObjectNode) MAPPER.readTree(buffer.asParser());
        }
        catch (JsonProcessingException e) {
            throw serializationError(artifact, e);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The fields of an artifact class a model may read, by their serialized names: every
     * property the class is written with, less the ones marked {@code @LLMContextIgnore}.
     * A field that holds null is among them, though {@link #modelFacingFields} leaves it out.
     *
     * @param type the artifact's class
     * @return the serialized names, in the order the class is written
     */
    public static Set<String> modelFacingFieldNames(Class<? extends Artifact> type) {
        Set<String> names = new LinkedHashSet<>();
        for (BeanPropertyDefinition property : MAPPER.getSerializationConfig().introspect(MAPPER.constructType(type)).findProperties()) {
            if (property.couldSerialize() && (property.getField() == null || !property.getField().hasAnnotation(LLMContextIgnore.class))) {
                names.add(property.getName());
            }
        }
        return names;
    }

    /**
     * The serialized name of a property, from either spelling of it: {@code fullText} and
     * {@code full_text} both give {@code full_text}.
     *
     * @param name a field's name as Java or as JSON spells it
     * @return the name the field is serialized under
     */
    public static String serializedName(String name) {
        return PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE.translate(name);
    }

    /**
     * Same as {@link #writeSummarized(Object)} but compact (no pretty-print).
     * For observability storage (a job record's artifact columns), where the bytes are
     * kept forever and every reader parses before rendering.
     *
     * @param obj the object to serialize
     * @return compact JSON with long fields summarized
     */
    public static String writeSummarizedCompact(Object obj) {
        try {
            return MAPPER.writer()
                .withAttribute(ATTR_MODE, MODE_SUMMARIZED)
                .writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }


    /**
     * Serialize for model input: every artifact, the object itself included, is replaced with
     * {@code {"@ref": "..."}} and registered in the registry, and no
     * {@code @LLMContextIgnore} field is written. An artifact's fields, and so its
     * summaries, are shown by the registry's own listing, never here.
     * Use right before passing content to the model. An artifact that reaches this mode with
     * no reference and no registry to mint one is a framework fault: the write fails with an
     * {@link IllegalStateException} naming the artifact in its cause chain.
     *
     * @param obj the object to serialize
     * @param registry the artifact registry for discovery and registration
     * @return pretty-printed JSON with artifacts as refs
     */
    public static String writeSummarizedWithRefs(Object obj, ArtifactRegistry registry) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter()
                .withAttribute(ATTR_MODE, MODE_LLM_REF)
                .withAttribute(ATTR_REGISTRY, registry)
                .writeValueAsString(obj);
        }
        catch (JsonProcessingException e) {
            throw serializationError(obj, e);
        }
    }

    // --- Reading ---

    /**
     * Parse raw LLM output to target class: the last balanced JSON span of the text is taken,
     * raw control characters inside its strings are escaped, typographic punctuation outside
     * string values is rewritten to ASCII, an answer wrapped under its type name or written in
     * the schema's own shape is unwrapped, and the lenient mapper reads it. A text with no
     * balanced span is refused as an extraction failure; a span that will not map is refused as
     * a mapping failure that names the target.
     *
     * @param raw the raw LLM response text
     * @param target the target class
     * @return parsed object
     * @throws IOException if parsing fails
     */
    public static <T> T parseLLMResponse(String raw, Class<T> target) throws IOException {
        String cleaned;
        try {
            cleaned = cleanJson(raw);
        }
        catch (IOException e) {
            // No balanced JSON span: an extraction failure, distinct from a mapping failure
            throw new IOException("Failed to extract valid JSON from response: " + e.getMessage(), e);
        }
        cleaned = unwrapTypeEcho(cleaned, target);
        cleaned = stripMetadataFields(cleaned);
        try {
            return MAPPER.readValue(cleaned, target);
        }
        catch (JsonProcessingException e) {
            log.debug("Failed JSON content (first 1000 chars): {}", cleaned.substring(0, Math.min(1000, cleaned.length())));
            throw new IOException("Failed to parse JSON to " + target.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Parse JSON to target class, for internal and API JSON. Text that does not parse as it is
     * gets one repair, raw control characters inside strings escaped and typographic punctuation
     * outside strings rewritten to ASCII, before it is refused. Empty input is refused.
     *
     * @param json the JSON string
     * @param target the target class
     * @return parsed object
     * @throws IOException if parsing fails
     */
    public static <T> T parse(String json, Class<T> target) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            throw new IOException("JSON is empty or null");
        }
        try {
            if (isValidJson(json)) {
                return MAPPER.readValue(json, target);
            }
            String cleaned = escapeControlCharactersInStrings(json);
            cleaned = normalizeUnicodePunctuation(cleaned);
            return MAPPER.readValue(cleaned, target);
        }
        catch (JsonProcessingException e) {
            throw new IOException("Failed to parse JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parse JSON string to a JsonNode tree for navigation.
     *
     * @param json the JSON string
     * @return the parsed tree
     * @throws IOException if parsing fails
     */
    public static JsonNode readTree(String json) throws IOException {
        return MAPPER.readTree(json);
    }

    /**
     * Convert between types via Jackson (e.g., POJO to Map, Map to POJO).
     *
     * @param value the source value
     * @param target the target type
     * @return the converted value
     */
    public static <T> T convert(Object value, Class<T> target) {
        return MAPPER.convertValue(value, target);
    }

    /**
     * The names of the JSON properties {@link #convert} can set on a type, spelled the way
     * this serializer reads them. A property of the source that is not among them is one the
     * type has no field for, which {@code convert} passes over in silence.
     */
    public static Set<String> settableProperties(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (BeanPropertyDefinition property : MAPPER.getDeserializationConfig().introspect(MAPPER.constructType(type)).findProperties()) {
            if (property.couldDeserialize()) {
                names.add(property.getName());
            }
        }
        return names;
    }

    // --- Tree API ---

    /**
     * Create an empty ObjectNode for building JSON structures.
     */
    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    /**
     * Create an empty ArrayNode for building JSON arrays.
     */
    public static ArrayNode createArrayNode() {
        return MAPPER.createArrayNode();
    }

    /**
     * Convert a value to a JsonNode tree.
     */
    public static JsonNode valueToTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * True if the class is a user-defined composite that Jackson would
     * structure-deserialize from a JSON object. False for primitives, enums,
     * arrays, collections, maps, the JDK scalar/temporal types in
     * {@link #LEAF_TYPES}, and anything in a {@code java.*}/{@code javax.*}/
     * {@code jakarta.*} package.
     *
     * <p>Callers use this to decide whether to recurse into a type's fields
     * (schema generation) or to attempt a {@code Map -> POJO} conversion
     * (runtime type recovery after generic erasure).
     */
    public static boolean isComposite(Class<?> c) {
        if (c == null || c == Object.class) return false;
        if (c.isPrimitive() || c.isEnum() || c.isArray()) return false;
        if (Collection.class.isAssignableFrom(c) || Map.class.isAssignableFrom(c)) return false;
        if (LEAF_TYPES.contains(c)) return false;
        String pkg = c.getPackageName();
        if (pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jakarta.")) return false;
        return true;
    }

    // ========================= Mapper Configuration =========================

    private static ObjectMapper createMapper() {
        // Custom LLM coercion deserializers
        SimpleModule llmModule = new SimpleModule("LLMCoercion");
        llmModule.addDeserializer(Boolean.class, new LLMBooleanDeserializer(false));
        llmModule.addDeserializer(boolean.class, new LLMBooleanDeserializer(true));
        llmModule.addDeserializer(Integer.class, new LLMIntegerDeserializer(false));
        llmModule.addDeserializer(int.class, new LLMIntegerDeserializer(true));
        llmModule.addDeserializer(LocalDate.class, new LLMLocalDateDeserializer());
        llmModule.addDeserializer(LocalDateTime.class, new LLMLocalDateTimeDeserializer());
        // A field declared as the Artifact INTERFACE resolves its concrete type from its own
        // ref, the same way a conversation's artifact map and a list's iterands do. Without
        // this, "some artifact, whatever it turns out to be" is not a shape a field can
        // declare: Jackson cannot construct an interface and fails the whole read.
        llmModule.addDeserializer(Artifact.class, new ArtifactRefDeserializer());
        llmModule.setDeserializerModifier(new LLMEnumDeserializerModifier());

        // Custom serialization module for @LLMSummarizable and Artifact
        SimpleModule serializationModule = new SimpleModule("NucleoSerialization");
        serializationModule.setSerializerModifier(new NucleoSerializerModifier());

        return JsonMapper.builder()
                // Lenient parsing for LLM output
                .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES, JsonReadFeature.ALLOW_SINGLE_QUOTES,
                        JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA,
                        JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER, JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                // A bean with no serializable properties (e.g. a no-argument tool input) is a legitimate
                // empty object, not an error; serialize it as {}. Without this, such inputs throw and the
                // failure is either swallowed (observability) or fatal at the unguarded write() call sites.
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                // A date or a time is written as ISO-8601 text, the shape its schema publishes
                // (`date`, `date-time`): a plain date as "2021-07-28", a timestamp with its
                // offset. Jackson's numeric forms ([2021,7,28], epoch seconds) say nothing to a
                // model. Reading still accepts text, arrays and numbers.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // A timestamp read keeps the offset it was written with in place of being moved
                // to UTC, so a written value reads back at the same offset and local time. The
                // name of a zone ("[Europe/Paris]") is not written: it is no part of an
                // ISO-8601 `date-time`, and a consumer told to expect that format refuses it.
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // snake_case <-> camelCase
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                // Java time support
                .addModule(new JavaTimeModule())
                .addModule(llmModule)
                .addModule(serializationModule)
                // Prompt serializer/deserializer: {key, content}; PromptContext is @JsonIgnore lineage
                .addModule(new ai.redouble.nucleo.prompt.PromptJacksonModule())
                .build();
    }

    // ========================= Custom Serialization Module =========================

    /**
     * Modifies serializers for three concerns:
     * 1. Artifacts: written as @ref wherever the mode shows a reference in place of content
     * 2. @LLMSummarizable fields, of any type: summarized when an artifact is shown to a model
     * 3. @LLMContextIgnore fields: left out of every write a model reads
     */
    static class NucleoSerializerModifier extends BeanSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc, List<BeanPropertyWriter> beanProperties) {
            for (int i = 0; i < beanProperties.size(); i++) {
                BeanPropertyWriter writer = beanProperties.get(i);
                if (writer.getAnnotation(LLMContextIgnore.class) != null) {
                    // Schema-and-storage only: skipped in every write a model reads, kept
                    // in the plain ones. Outermost wrapper so the skip wins before any
                    // summarization would run.
                    beanProperties.set(i, new ContextIgnorePropertyWriter(writer));
                    continue;
                }
                if (ArtifactResponse.class.isAssignableFrom(beanDesc.getBeanClass()) && SELECTED_REFS_PROPERTY.equals(writer.getName())) {
                    beanProperties.set(i, new SelectedArtifactsPropertyWriter(writer));
                    continue;
                }
                LLMSummarizable anno = writer.getAnnotation(LLMSummarizable.class);
                if (anno != null) {
                    beanProperties.set(i, new SummarizablePropertyWriter(writer, anno));
                }
            }
            return beanProperties;
        }

        @Override
        @SuppressWarnings("unchecked")
        public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
            if (ListArtifact.class.isAssignableFrom(beanDesc.getBeanClass())) {
                // Composition order: LLM_REF mode short-circuits to @ref, SUMMARIZED mode
                // renders the digest, everything else falls through to the full bean form.
                return new ArtifactRefSerializer(new ListArtifactDigestSerializer((JsonSerializer<Object>) serializer));
            }
            if (Artifact.class.isAssignableFrom(beanDesc.getBeanClass())) {
                return new ArtifactRefSerializer((JsonSerializer<Object>) serializer);
            }
            return serializer;
        }
    }

    /**
     * Renders a {@link ListArtifact} as a bounded digest in summarized mode:
     * {@code {artifact_ref, iterand_type, count, sample}} with the sample limited to
     * the first {@value #SAMPLE_SIZE} iterands. This is the one artifact type whose
     * prompt-form is intentionally not recursive - it is what keeps prompt cost
     * independent of list size. Embedded list FIELDS on regular artifacts keep their
     * normal rendering; this serializer binds to the ListArtifact class only.
     * Outside summarized mode (plain {@link #write}), the full bean form is written
     * so persistence and replay keep every iterand.
     */
    static class ListArtifactDigestSerializer extends JsonSerializer<Object> {
        static final int SAMPLE_SIZE = 3;
        private final JsonSerializer<Object> delegate;
        ListArtifactDigestSerializer(JsonSerializer<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            String mode = (String) provider.getAttribute(ATTR_MODE);
            if (!MODE_SUMMARIZED.equals(mode)) {
                delegate.serialize(value, gen, provider);
                return;
            }
            ListArtifact<?> list = (ListArtifact<?>) value;
            gen.writeStartObject();
            if (list.getArtifactRef() != null) {
                gen.writeStringField("artifact_ref", list.getArtifactRef());
            }
            if (list.getIterandTypeAlias() != null) {
                gen.writeStringField("iterand_type", list.getIterandTypeAlias());
            }
            int count = list.getIterands() == null ? 0 : list.getIterands().size();
            gen.writeNumberField("count", count);
            if (count > 0) {
                gen.writeArrayFieldStart("sample");
                for (int i = 0; i < Math.min(SAMPLE_SIZE, count); i++) {
                    provider.defaultSerializeValue(list.getIterands().get(i), gen);
                }
                gen.writeEndArray();
            }
            gen.writeEndObject();
        }
    }

    /**
     * Wraps an Artifact serializer. In LLM_REF mode, writes @ref, minting and registering the
     * reference through the registry when the artifact has none; with no reference and no
     * registry it throws {@link IllegalStateException}, a framework fault. STORED mode does
     * the same. In FIELDS mode
     * every artifact but the one being read is written as @ref too, with nothing minted and
     * nothing registered. In all other modes, delegates to the default bean serializer.
     */
    static class ArtifactRefSerializer extends JsonSerializer<Object> {
        private final JsonSerializer<Object> delegate;
        ArtifactRefSerializer(JsonSerializer<Object> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            String mode = (String) provider.getAttribute(ATTR_MODE);
            boolean insideTheOneRead = MODE_FIELDS.equals(mode) && value != provider.getAttribute(ATTR_ROOT);
            if ((MODE_LLM_REF.equals(mode) || MODE_STORED.equals(mode) || insideTheOneRead) && value instanceof Artifact artifact) {
                // Register in registry
                ArtifactRegistry registry = (ArtifactRegistry) provider.getAttribute(ATTR_REGISTRY);
                if (registry != null) {
                    registry.ensureReference(artifact);
                    if (artifact instanceof ArtifactResponse<?> response) {
                        registerSelected(response, registry);
                    }
                }
                String ref = artifact.getArtifactRef();
                if (ref == null) {
                    throw new IllegalStateException(
                        "Artifact of type " + value.getClass().getSimpleName() + " has no ref assigned. " +
                        "Ensure an ArtifactRegistry is passed to writeSummarizedWithRefs(). " +
                        "This is a framework bug - the artifact was created but could not be registered.");
                }
                gen.writeStartObject();
                gen.writeStringField(ARTIFACT_REF_AS_SHOWN, ref);
                gen.writeEndObject();
                return;
            }
            delegate.serialize(value, gen, provider);
        }
    }

    /**
     * Property writer for {@code @LLMContextIgnore} fields: omits the property in every
     * write a model reads (the prompt, the registry's listing of an artifact, an artifact
     * read back whole) and delegates in the plain ones, so the field still serializes for
     * the schema-driven full {@link #write} (persistence and observability). See
     * {@link LLMContextIgnore}.
     */
    static class ContextIgnorePropertyWriter extends BeanPropertyWriter {
        private final BeanPropertyWriter delegate;

        ContextIgnorePropertyWriter(BeanPropertyWriter delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider provider) throws Exception {
            Object mode = provider.getAttribute(ATTR_MODE);
            if (mode != null && !MODE_STORED.equals(mode)) {
                return;
            }
            delegate.serializeAsField(bean, gen, provider);
        }
    }

    /**
     * Registers, as entries of their own, the artifacts a response selected: how the
     * artifacts a called thinker selected reach the registry of the thinker that called it,
     * whether the response is written with its fields or, being an artifact itself, as a
     * reference.
     */
    private static void registerSelected(ArtifactResponse<?> response, ArtifactRegistry registry) {
        Collection<Artifact> selected = response.selectedArtifacts();
        if (selected == null) {
            return;
        }
        for (Artifact artifact : selected) {
            registry.ensureReference(artifact);
        }
    }

    /**
     * Property writer for {@code artifact_refs} of an {@link ArtifactResponse}. In LLM_REF
     * mode a response that holds the objects it selected
     * ({@link ArtifactResponse#selectedArtifacts}) has the property written from them: each
     * goes through {@link ArtifactRefSerializer}, which writes its reference and registers it
     * with the registry of the conversation being written for. That is how the artifacts a
     * called thinker selected reach the thinker that called it. A reference of the list that
     * none of the objects carries follows them as a string. In every other mode, and for a
     * response that holds no objects, the list of strings is written as it is.
     */
    static class SelectedArtifactsPropertyWriter extends BeanPropertyWriter {
        private final BeanPropertyWriter delegate;

        SelectedArtifactsPropertyWriter(BeanPropertyWriter delegate) {
            super(delegate);
            this.delegate = delegate;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider provider) throws Exception {
            ArtifactResponse<?> response = (ArtifactResponse<?>) bean;
            Collection<Artifact> selected = response.selectedArtifacts();
            if (!MODE_LLM_REF.equals(provider.getAttribute(ATTR_MODE)) || selected == null || selected.isEmpty()) {
                delegate.serializeAsField(bean, gen, provider);
                return;
            }
            Set<String> written = new HashSet<>();
            gen.writeArrayFieldStart(delegate.getName());
            for (Artifact artifact : selected) {
                provider.defaultSerializeValue(artifact, gen);
                written.add(ArtifactRegistry.normalizeToKey(artifact.getArtifactRef()));
            }
            if (response.getArtifactRefs() != null) {
                for (String ref : response.getArtifactRefs()) {
                    if (!written.contains(ArtifactRegistry.normalizeToKey(ref))) {
                        gen.writeString(ref);
                    }
                }
            }
            gen.writeEndArray();
        }
    }

    /**
     * Property writer of an {@code @LLMSummarizable} field, of any type.
     *
     * <p>The field is summarized in one write only: {@code SUMMARIZED}, where an artifact is
     * shown with its fields. Every other mode writes it whole, and so does a bean that is
     * no {@link Artifact}, which has neither a place to keep a summary nor a reference to
     * read the whole value by.
     *
     * <p>What is measured and summarized is the field as the serializer writes it without
     * the annotation: a text as it is, any other value as its compact JSON, in which an
     * artifact inside it is already shown with its own summaries. A value no longer than
     * the threshold is written as it is.
     */
    static class SummarizablePropertyWriter extends BeanPropertyWriter {
        private final BeanPropertyWriter delegate;
        private final LLMSummarizable annotation;
        private final String name;
        /** The summarizer the field names for itself; null when it names none or the class could not be made. */
        private final FieldSummarizer<?> named;

        SummarizablePropertyWriter(BeanPropertyWriter delegate, LLMSummarizable annotation) {
            super(delegate);
            this.delegate = delegate;
            this.annotation = annotation;
            this.name = delegate.getName();
            this.named = annotation.summarizer() != FieldSummarizer.None.class ? instantiate(annotation.summarizer()) : null;
        }

        /** The summarizer of the given class, or null, with the failure logged, when the class cannot be made. */
        private static FieldSummarizer<?> instantiate(Class<? extends FieldSummarizer<?>> summarizerClass) {
            try {
                return summarizerClass.getConstructor().newInstance();
            }
            catch (ReflectiveOperationException e) {
                log.error("The summarizer {} named by @LLMSummarizable needs a public no-argument constructor; its fields are cut short as plain text",
                          summarizerClass.getName(), e);
                return null;
            }
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider provider) throws Exception {
            if (!MODE_SUMMARIZED.equals(provider.getAttribute(ATTR_MODE)) || !(bean instanceof Artifact artifact)) {
                delegate.serializeAsField(bean, gen, provider);
                return;
            }
            Object value = delegate.get(bean);
            if (value == null) {
                delegate.serializeAsField(bean, gen, provider);
                return;
            }
            if (annotation.preSummarized()) {
                gen.writeFieldName(name);
                writePreSummarized(value, "", artifact, gen, provider);
                return;
            }
            if (value instanceof String text) {
                if (text.length() <= annotation.threshold()) {
                    delegate.serializeAsField(bean, gen, provider);
                    return;
                }
                warnIfMissingHint(bean.getClass(), name, annotation);
                gen.writeFieldName(name);
                gen.writeString(summary(artifact, name, text, text, provider));
                return;
            }
            // Any other value is written once, aside, as it would be without the annotation
            TokenBuffer aside = new TokenBuffer(MAPPER, false);
            aside.writeStartObject();
            delegate.serializeAsField(bean, aside, provider);
            aside.writeEndObject();
            JsonNode written = ((JsonNode) MAPPER.readTree(aside.asParser())).get(name);
            if (written == null) {
                return;
            }
            String json = MAPPER.writeValueAsString(written);
            gen.writeFieldName(name);
            if (json.length() <= annotation.threshold()) {
                gen.writeTree(written);
                return;
            }
            warnIfMissingHint(bean.getClass(), name, annotation);
            gen.writeString(summary(artifact, name, value, json, provider));
        }

        /**
         * What a model is shown in place of a long value: the one decision every summary
         * is made by. A kept summary first; else the fixed phrase; else the named class;
         * else a cut, when the content must not be paraphrased or is longer than a model
         * is asked to summarize; else a model's summary. Each of those is kept.
         *
         * <p>A model is asked only in a write that carries one. Without one, and when the
         * named class or the model's job fails, the text is cut for this write and nothing
         * is kept, so a later write that has a model asks again.
         *
         * @param key the key the summary is kept under
         * @param value what a named class is handed: the field's value
         * @param text the value as the serializer writes it, which is what is cut or sent to a model
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private String summary(Artifact artifact, String key, Object value, String text, SerializerProvider provider) {
            ArtifactSummaries summaries = artifact.getSummaries();
            String kept = summaries.get(key);
            if (kept != null) {
                return kept;
            }
            if (!annotation.staticSummary().isEmpty()) {
                return summaries.keep(key, String.format("[SUMMARY: %d chars] %s", text.length(), annotation.staticSummary()));
            }
            if (annotation.summarizer() != FieldSummarizer.None.class) {
                return made(summaries, key, text, artifact, annotation.summarizer().getName(), () -> {
                    if (named == null) {
                        throw new IllegalStateException(annotation.summarizer().getName() + " could not be made");
                    }
                    return ((FieldSummarizer) named).summarize(value, annotation);
                });
            }
            if (!annotation.llmSafe() || text.length() > MODEL_SUMMARY_MAX_CHARS) {
                return summaries.keep(key, CUT.summarize(text, annotation));
            }
            Summarizer model = (Summarizer) provider.getAttribute(ATTR_MODEL);
            if (model == null || Thread.currentThread().isInterrupted()) {
                return CUT.summarize(text, annotation);
            }
            return made(summaries, key, text, artifact, model.getClass().getName(), () -> model.summarize(text, annotation));
        }

        /**
         * The summary the maker makes, kept; or a cut of the text with nothing kept, when
         * the maker throws or returns nothing, or when another thread's making of the same
         * summary failed. An interruption leaves the thread's flag set.
         */
        private String made(ArtifactSummaries summaries, String key, String text, Artifact artifact, String maker, Callable<String> making) {
            try {
                String summary = summaries.make(key, () -> {
                    String answer = making.call();
                    if (answer == null) {
                        throw new IllegalStateException(maker + " returned no summary");
                    }
                    return answer;
                });
                if (summary != null) {
                    return summary;
                }
            }
            catch (Exception e) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
                log.warn("{} made no summary of {} of {}; the text is cut for this write and nothing is kept",
                         maker, key, artifact.getClass().getSimpleName(), e);
            }
            return CUT.summarize(text, annotation);
        }

        /**
         * Writes a tree whose long text leaves are summarized one by one: walks Map and
         * List shapes, and a text leaf longer than the threshold is written as its
         * summary, decided as any field's is and kept under the field's name followed by
         * the leaf's JSON pointer, e.g. {@code data/results/0/fullText}. Everything else
         * goes to the provider's default serializer, so an artifact inside the tree is
         * written as the mode writes artifacts.
         */
        private void writePreSummarized(Object value, String path, Artifact artifact,
                                        JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (value instanceof String text && text.length() > annotation.threshold()) {
                gen.writeString(summary(artifact, name + path, text, text, provider));
                return;
            }
            if (value == null) {
                gen.writeNull();
                return;
            }
            if (value instanceof Map<?, ?> map) {
                gen.writeStartObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    String childPath = path + "/" + escapePointerToken(key);
                    gen.writeFieldName(key);
                    writePreSummarized(e.getValue(), childPath, artifact, gen, provider);
                }
                gen.writeEndObject();
                return;
            }
            if (value instanceof List<?> list) {
                gen.writeStartArray();
                for (int i = 0; i < list.size(); i++) {
                    writePreSummarized(list.get(i), path + "/" + i, artifact, gen, provider);
                }
                gen.writeEndArray();
                return;
            }
            // Primitive, Artifact, or other polymorphic value - hand to default serializer
            // so ArtifactRefSerializer / scalar serializers fire as usual.
            provider.defaultSerializeValue(value, gen);
        }

        private static String escapePointerToken(String s) {
            if (s.indexOf('~') < 0 && s.indexOf('/') < 0) {
                return s;
            }
            return s.replace("~", "~0").replace("/", "~1");
        }
    }


    // ========================= JSON Cleaning =========================

    /**
     * Extracts the JSON payload from an LLM response.
     * <p>
     * LLMs emit reasoning preamble followed by the final JSON, so the JSON is the
     * last top-level balanced bracketed span. A single forward pass tracks string
     * state and depth; every time depth returns to 0 from a top-level <code>{</code>
     * or <code>[</code>, the span is recorded. The last recorded span is returned.
     * <p>
     * This handles, without any target-type branching:
     * <ul>
     *   <li>Preamble that contains bracket-looking text ([1,2,3], [text](url), imidazo[4,5-c])</li>
     *   <li>Object answers and array answers with identical logic</li>
     *   <li>Markdown fences ({@code ```json ... ```}) - the fence body is the last span</li>
     *   <li>Trailing prose after the JSON</li>
     *   <li>String values containing unbalanced brace or bracket characters</li>
     * </ul>
     *
     * @param raw the raw LLM response text
     * @return the JSON substring
     * @throws IOException if no balanced top-level JSON span is present
     */
    public static String extractJsonFromLLMResponse(String raw) throws IOException {
        if (raw == null || raw.isEmpty()) {
            throw new IOException("Empty response");
        }
        int lastStart = -1;
        int lastEnd = -1;
        int topStart = -1;
        char topOpen = 0;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    escaped = true;
                }
                else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{' || c == '[') {
                if (depth == 0) {
                    topStart = i;
                    topOpen = c;
                }
                depth++;
            }
            else if (c == '}' || c == ']') {
                if (depth == 0) {
                    continue; // stray closer in preamble, ignore
                }
                char expected = (topOpen == '{') ? '}' : ']';
                depth--;
                if (depth == 0) {
                    if (c == expected) {
                        lastStart = topStart;
                        lastEnd = i;
                    }
                    // Mismatched closer at depth 0 means the span was malformed; discard.
                    topStart = -1;
                    topOpen = 0;
                }
            }
        }
        if (lastStart == -1) {
            throw new IOException("No JSON object or array found in the response");
        }
        return raw.substring(lastStart, lastEnd + 1);
    }

    /**
     * Splits an LLM response into the embedded JSON span and the prose around it.
     * The JSON is the last top-level balanced span (same definition as
     * {@link #extractJsonFromLLMResponse(String)}). The prose is everything else with
     * surrounding whitespace trimmed. Either field may be empty.
     *
     * <p>For response handlers that keep the freeform reasoning a model wrote alongside a
     * structured answer.
     *
     * @param raw the raw LLM response text (may be null or empty)
     * @return a split with populated {@code json()} and {@code prose()} fields
     */
    public static JsonProseSplit extractJsonWithProse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new JsonProseSplit("", "");
        }
        try {
            String json = extractJsonFromLLMResponse(raw);
            int start = raw.indexOf(json);
            String prose;
            if (start < 0) {
                prose = raw.trim();
            }
            else {
                String before = raw.substring(0, start);
                String after = raw.substring(start + json.length());
                prose = (before + " " + after).trim();
            }
            return new JsonProseSplit(json, prose);
        }
        catch (IOException e) {
            return new JsonProseSplit("", raw.trim());
        }
    }

    /** Pair of (json, prose) returned by {@link #extractJsonWithProse(String)}. */
    public record JsonProseSplit(String json, String prose) {}

    /**
     * Cleans raw LLM JSON - extracts JSON, escapes control chars, normalizes Unicode.
     */
    static String cleanJson(String llmResponse) throws IOException {
        if (llmResponse == null || llmResponse.trim().isEmpty()) {
            throw new IOException("Empty response");
        }
        String jsonStr = extractJsonFromLLMResponse(llmResponse);
        jsonStr = escapeControlCharactersInStrings(jsonStr);
        jsonStr = normalizeUnicodePunctuation(jsonStr);
        return jsonStr;
    }

    /**
     * Escapes control characters inside JSON string values.
     */
    private static String escapeControlCharactersInStrings(String json) {
        StringBuilder result = new StringBuilder(json.length() + 100);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                result.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                result.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                result.append(c);
                inString = !inString;
                continue;
            }
            if (inString) {
                switch (c) {
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    case '\f' -> result.append("\\f");
                    case '\b' -> result.append("\\b");
                    default -> {
                        if (c < 32) {
                            result.append(String.format("\\u%04x", (int) c));
                        } else {
                            result.append(c);
                        }
                    }
                }
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /**
     * Rewrites typographic punctuation to ASCII outside string values only: curly quotes around
     * a key or a value become the quotes the parser reads, a dash or an ellipsis between tokens
     * becomes its ASCII form, and what a model wrote inside a value stays as written. A string
     * opened by a straight quote closes on the same straight quote; one opened by a curly quote
     * closes on its curly counterpart; a backslash escapes the next character.
     */
    private static String normalizeUnicodePunctuation(String json) {
        StringBuilder out = new StringBuilder(json.length());
        char closer = 0;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (closer != 0) {
                if (escaped) {
                    escaped = false;
                    out.append(c);
                }
                else if (c == '\\') {
                    escaped = true;
                    out.append(c);
                }
                else if (c == closer) {
                    out.append(ascii(c));
                    closer = 0;
                }
                else {
                    out.append(c);
                }
                continue;
            }
            switch (c) {
                case '"', '\'' -> {
                    closer = c;
                    out.append(c);
                }
                case '\u201C' -> {
                    closer = '\u201D';
                    out.append('"');
                }
                case '\u2018' -> {
                    closer = '\u2019';
                    out.append('\'');
                }
                default -> out.append(ascii(c));
            }
        }
        return out.toString();
    }

    private static String ascii(char c) {
        return switch (c) {
            case '\u2026' -> "...";
            case '\u2018', '\u2019', '\u2032' -> "'";
            case '\u201C', '\u201D', '\u2033' -> "\"";
            case '\u2013' -> "-";
            case '\u2014' -> "--";
            default -> String.valueOf(c);
        };
    }

    /**
     * One echo of the schema: the instance wrapped under the type's own name, the
     * string the notation prints as {@code @type} - {@code {"ThinkingResponse": {...}}} -
     * observed on the OpenAI reasoning family once a correction had been appended. The
     * wrapper is one object with exactly one member named after the target type and an
     * object under it; that object is the instance. An object with any other shape, a
     * type whose single field happens to share the type's name included, is left as it is.
     */
    private static String unwrapTypeEcho(String json, Class<?> target) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root.isObject() && root.size() == 1) {
                JsonNode wrapped = root.get(target.getSimpleName());
                if (wrapped != null && wrapped.isObject()) {
                    return MAPPER.writeValueAsString(wrapped);
                }
            }
            return json;
        }
        catch (Exception e) {
            log.debug("Failed to read the response for a type-name wrapper, returning original: {}", e.getMessage());
            return json;
        }
    }

    /**
     * The other echo of the schema. The response contract renders schemas as
     * {@code {"@type", "@description", "@fields": {...}}} and asks for an INSTANCE, but
     * format-literal models (the Nova family, observed on real decode traffic) answer in the
     * schema's own shape with their values filled into {@code @fields}. That answer is an
     * isomorphic encoding of the instance: hoisting every {@code @fields} object into its
     * parent, recursively, and dropping the {@code @}-prefixed metadata keys recovers exactly
     * the instance the model meant. Left in schema shape, the answer maps to an all-null POJO
     * and reads as a silent decline.
     */
    private static String stripMetadataFields(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            return MAPPER.writeValueAsString(normalizeSchemaEcho(root));
        }
        catch (Exception e) {
            log.debug("Failed to strip metadata fields, returning original: {}", e.getMessage());
            return json;
        }
    }

    private static JsonNode normalizeSchemaEcho(JsonNode node) {
        if (node.isArray()) {
            ArrayNode array = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                array.add(normalizeSchemaEcho(element));
            }
            return array;
        }
        if (!node.isObject()) {
            return node;
        }
        JsonNode fields = node.get("@fields");
        if (fields != null && fields.isObject()) {
            // schema echo: the instance lives inside @fields
            return normalizeSchemaEcho(fields);
        }
        ObjectNode out = MAPPER.createObjectNode();
        for (Map.Entry<String, JsonNode> member : node.properties()) {
            // "@ref" is how the model is shown an artifact, so a model referring to one may
            // write it back the same way: it is the artifact's reference under its shown name
            if (member.getKey().equals(ARTIFACT_REF_AS_SHOWN)) {
                out.set(ArtifactRegistry.REF_FIELD, member.getValue());
            }
            // every other @-prefixed key is schema notation, never a POJO field
            else if (!member.getKey().startsWith("@")) {
                out.set(member.getKey(), normalizeSchemaEcho(member.getValue()));
            }
        }
        return out;
    }

    private static boolean isValidJson(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            MAPPER.readTree(json);
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    // ========================= Helpers =========================

    private static void warnIfMissingHint(Class<?> clazz, String fieldName, LLMSummarizable annotation) {
        if (annotation.value() == null || annotation.value().isEmpty()) {
            String key = clazz.getName() + "." + fieldName;
            if (WARNED_MISSING_HINTS.add(key)) {
                log.warn("@LLMSummarizable on {}.{} has no content hint - summarizer will lack context", clazz.getSimpleName(), fieldName);
            }
        }
    }

    /** Past this a field is described by its size rather than reproduced. */
    private static final int FIELD_VALUE_CHARS = 200;

    /**
     * Describes the object a serialization failed on, WITHOUT reproducing it.
     * <p>
     * The obvious way to write this is to print every field, and it is a trap. The one
     * call that reaches here is the call whose object was too big to serialize, so
     * rendering that object again is a second copy of the thing that just exhausted the
     * buffer - and string-concatenating a collection field calls toString on every
     * element, which for a list of 5,127 elements without a toString of their own emits
     * 5,127 identity hashes onto one line. That is megabytes of
     * {@code ClassName@1bf29bef} naming nothing, on the one path where memory is
     * already gone.
     * <p>
     * A size is what a reader actually needs: which field is huge, and how huge. The
     * exception's own message says what Jackson objected to, and the cause carries the
     * stack, so this neither logs nor duplicates either.
     */
    private static RuntimeException serializationError(Object obj, JsonProcessingException e) {
        String className = obj != null ? obj.getClass().getName() : "null";
        StringBuilder debug = new StringBuilder();
        debug.append("Failed to serialize ").append(className).append(":\n");
        if (obj != null) {
            for (var field : obj.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try {
                    debug.append("  ")
                        .append(field.getType().getSimpleName())
                        .append(" ")
                        .append(field.getName())
                        .append(" = ")
                        .append(describe(field.get(obj)))
                        .append("\n");
                }
                catch (IllegalAccessException ignored) {
                    debug.append("  ").append(field.getName()).append(" = <inaccessible>\n");
                }
            }
        }
        return new RuntimeException(debug + e.getMessage(), e);
    }

    /**
     * One field as a bounded description. A collection, map or array reports how many it
     * holds and never what; a string reports its length and a prefix; anything else is
     * rendered and truncated, because a value that overrides toString into something
     * enormous is exactly as damaging here as a list.
     */
    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        String type = value.getClass().getName();
        if (value instanceof Collection<?> collection) {
            return type + "[" + collection.size() + " element(s)]";
        }
        if (value instanceof Map<?, ?> map) {
            return type + "[" + map.size() + " entry(s)]";
        }
        if (value.getClass().isArray()) {
            return type + "[" + Array.getLength(value) + " element(s)]";
        }
        if (value instanceof CharSequence text) {
            String head = text.length() <= FIELD_VALUE_CHARS ? text.toString()
                    : text.subSequence(0, FIELD_VALUE_CHARS) + "...";
            return type + "[" + text.length() + " chars]: " + head;
        }
        String rendered = String.valueOf(value);
        if (rendered.length() > FIELD_VALUE_CHARS) {
            rendered = rendered.substring(0, FIELD_VALUE_CHARS) + "...";
        }
        return type + ": " + rendered;
    }

    // ========================= Custom Deserializers =========================
    // The coercions: values a model spelled its own way, read into the type the field declares

    /**
     * A boolean from its usual spellings and from 0 and 1. Registered once for the wrapper and
     * once for the primitive: a JSON null is read by {@link #getNullValue}, and for the
     * primitive that is {@code false}, the default FAIL_ON_NULL_FOR_PRIMITIVES-off tolerance the
     * mapper promises, where a null would fail the setter.
     */
    static class LLMBooleanDeserializer extends JsonDeserializer<Boolean> {
        private final boolean primitive;

        LLMBooleanDeserializer(boolean primitive) {
            this.primitive = primitive;
        }

        @Override
        public Boolean getNullValue(DeserializationContext ctxt) {
            return primitive ? Boolean.FALSE : null;
        }

        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.getCurrentToken();
            if (token == JsonToken.VALUE_TRUE) return Boolean.TRUE;
            if (token == JsonToken.VALUE_FALSE) return Boolean.FALSE;
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                Boolean parsed = Parsing.yesNo(text.toLowerCase());
                if (parsed != null) return parsed;
                throw new InvalidFormatException(p, "Cannot convert '" + text + "' to boolean", text, Boolean.class);
            }
            if (token == JsonToken.VALUE_NUMBER_INT) {
                int value = p.getIntValue();
                if (value == 0) return Boolean.FALSE;
                if (value == 1) return Boolean.TRUE;
                throw new InvalidFormatException(p, "Cannot convert number " + value + " to boolean", value, Boolean.class);
            }
            if (token == JsonToken.VALUE_NULL) return null;
            throw new InvalidFormatException(p, "Cannot deserialize Boolean from " + token, token, Boolean.class);
        }
    }

    /**
     * An integer from a whole-number float or its text; a fraction is refused, an empty string is
     * null. Registered once for the wrapper and once for the primitive, whose JSON null reads as
     * zero for the same reason as the boolean's.
     */
    static class LLMIntegerDeserializer extends JsonDeserializer<Integer> {
        private final boolean primitive;

        LLMIntegerDeserializer(boolean primitive) {
            this.primitive = primitive;
        }

        @Override
        public Integer getNullValue(DeserializationContext ctxt) {
            return primitive ? 0 : null;
        }

        @Override
        public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.getCurrentToken();
            if (token == JsonToken.VALUE_NUMBER_INT) return p.getIntValue();
            if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                double value = p.getDoubleValue();
                if (value == Math.floor(value) && !Double.isInfinite(value)) return (int) value;
                throw new InvalidFormatException(p, "Cannot convert non-integer float " + value + " to integer", value, Integer.class);
            }
            if (token == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                if (text.isEmpty()) return null;
                try {
                    double d = Double.parseDouble(text);
                    if (d == Math.floor(d) && !Double.isInfinite(d)) return (int) d;
                    throw new InvalidFormatException(p, "Cannot convert '" + text + "' to integer (fractional value)", text, Integer.class);
                }
                catch (NumberFormatException e) {
                    throw new InvalidFormatException(p, "Cannot convert '" + text + "' to integer", text, Integer.class);
                }
            }
            if (token == JsonToken.VALUE_NULL) return null;
            throw new InvalidFormatException(p, "Cannot deserialize Integer from " + token, token, Integer.class);
        }
    }

    /**
     * A date from any shape {@link Temporals#parseLenient} reads (a date-time contributes its date
     * part), or from a {@code [y, m, d]} array; an empty string is null.
     */
    static class LLMLocalDateDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.getCurrentToken() == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                if (text.isEmpty()) return null;
                TemporalAccessor parsed = Temporals.parseLenient(text);
                if (parsed == null) {
                    throw new InvalidFormatException(p, "Cannot parse date: " + text, text, LocalDate.class);
                }
                return LocalDate.from(parsed);
            }
            if (p.getCurrentToken() == JsonToken.START_ARRAY) {
                int year = p.nextIntValue(0);
                int month = p.nextIntValue(0);
                int day = p.nextIntValue(0);
                p.nextToken();
                return LocalDate.of(year, month, day);
            }
            if (p.getCurrentToken() == JsonToken.VALUE_NULL) return null;
            throw new InvalidFormatException(p, "Expected string or array for LocalDate, got " + p.getCurrentToken(), p.getCurrentToken(), LocalDate.class);
        }
    }

    /**
     * A date-time from any shape {@link Temporals#parseLenient} reads (a date alone is the start
     * of that day), or from a {@code [y, m, d, h, min]} array with optional seconds and nanos; an
     * empty string is null.
     */
    static class LLMLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.getCurrentToken() == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                if (text.isEmpty()) return null;
                TemporalAccessor parsed = Temporals.parseLenient(text);
                if (parsed == null) {
                    throw new InvalidFormatException(p, "Cannot parse datetime: " + text, text, LocalDateTime.class);
                }
                if (parsed instanceof LocalDate date) {
                    return date.atStartOfDay();
                }
                return LocalDateTime.from(parsed);
            }
            if (p.getCurrentToken() == JsonToken.START_ARRAY) {
                int year = p.nextIntValue(0);
                int month = p.nextIntValue(0);
                int day = p.nextIntValue(0);
                int hour = p.nextIntValue(0);
                int minute = p.nextIntValue(0);
                int second = 0;
                int nano = 0;
                if (p.nextToken() == JsonToken.VALUE_NUMBER_INT) {
                    second = p.getIntValue();
                    if (p.nextToken() == JsonToken.VALUE_NUMBER_INT) {
                        nano = p.getIntValue();
                        p.nextToken();
                    }
                }
                return LocalDateTime.of(year, month, day, hour, minute, second, nano);
            }
            if (p.getCurrentToken() == JsonToken.VALUE_NULL) return null;
            throw new InvalidFormatException(p, "Expected string or array for LocalDateTime, got " + p.getCurrentToken(), p.getCurrentToken(), LocalDateTime.class);
        }
    }

    static class LLMEnumDeserializerModifier extends BeanDeserializerModifier {
        @Override
        public JsonDeserializer<?> modifyEnumDeserializer(DeserializationConfig config, JavaType type, BeanDescription beanDesc, JsonDeserializer<?> defaultDeserializer) {
            Class<?> enumClass = type.getRawClass();
            if (!enumClass.isEnum()) return defaultDeserializer;
            return new LLMEnumDeserializer(enumClass, defaultDeserializer);
        }
    }

    /**
     * An enum by its exact constant name, then case-insensitively, then from an object whose first
     * textual member names it; a name that is no constant is refused.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static class LLMEnumDeserializer extends JsonDeserializer<Enum> {
        private final Class<? extends Enum> enumClass;
        private final JsonDeserializer<?> delegate;
        LLMEnumDeserializer(Class<?> enumClass, JsonDeserializer<?> delegate) {
            this.enumClass = (Class<? extends Enum>) enumClass;
            this.delegate = delegate;
        }
        @Override
        public Enum deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (p.currentToken() == JsonToken.START_OBJECT) {
                JsonNode node = p.readValueAsTree();
                for (Map.Entry<String, JsonNode> entry : node.properties()) {
                    if (entry.getValue().isTextual()) {
                        return resolveEnum(entry.getValue().asText().trim(), p);
                    }
                }
                throw new InvalidFormatException(p, "Cannot deserialize " + enumClass.getSimpleName() + " from object: " + node, node, enumClass);
            }
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String text = p.getText().trim();
                try {
                    return resolveEnum(text, p);
                }
                catch (JsonMappingException e) {
                    return (Enum) delegate.deserialize(p, ctxt);
                }
            }
            return (Enum) delegate.deserialize(p, ctxt);
        }
        private Enum resolveEnum(String text, JsonParser p) throws JsonMappingException {
            for (Enum constant : enumClass.getEnumConstants()) {
                if (constant.name().equals(text)) return constant;
            }
            for (Enum constant : enumClass.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(text)) return constant;
            }
            throw new InvalidFormatException(p, "No enum constant " + enumClass.getSimpleName() + "." + text, text, enumClass);
        }
    }
}
