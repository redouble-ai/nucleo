/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.std.*;
import com.fasterxml.jackson.databind.module.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.ser.std.*;

import java.io.*;

/**
 * Jackson module registering serializer/deserializer for {@link Prompt}.
 *
 * <p>Wire format is {@code {"key": "<k>", "content": <node>}}. The {@link PromptContext}
 * is never emitted (it is lineage, not content). On deserialization every Prompt is
 * materialized as a {@link TextPrompt}; the {@code content} node may be {@code TextNode}
 * for text prompts or {@code ObjectNode} for structured prompts - polymorphism is in the
 * content shape, not the Prompt type. A document that is not a JSON object, or that lacks
 * a textual {@code key}, is refused; an absent {@code content} restores as an explicit
 * {@code NullNode}, never a Java null.
 *
 * <p>Registered into {@code NucleoJsonSerializer}'s ObjectMapper so every path that uses
 * the framework serializer (write, writeSummarized, writeSummarizedWithRefs, parseLLMResponse)
 * sees the same shape.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-20)
 */
public class PromptJacksonModule extends SimpleModule {
    public PromptJacksonModule() {
        super("PromptJacksonModule");
        addSerializer(Prompt.class, new PromptSerializer());
        addDeserializer(Prompt.class, new PromptDeserializer());
    }

    private static final class PromptSerializer extends StdSerializer<Prompt> {
        PromptSerializer() {
            super(Prompt.class);
        }

        @Override
        public void serialize(Prompt value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeStartObject();
            gen.writeStringField("key", value.key());
            gen.writeFieldName("content");
            JsonNode content = value.content();
            if (content == null) {
                gen.writeNull();
            }
            else {
                provider.defaultSerializeValue(content, gen);
            }
            gen.writeEndObject();
        }
    }

    private static final class PromptDeserializer extends StdDeserializer<Prompt> {
        PromptDeserializer() {
            super(Prompt.class);
        }

        @Override
        public Prompt deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            if (!node.isObject()) {
                throw new JsonMappingException(p, "Prompt must be a JSON object with 'key' and 'content' fields");
            }
            JsonNode keyNode = node.get("key");
            if (keyNode == null || !keyNode.isTextual()) {
                throw new JsonMappingException(p, "Prompt.key missing or not a string");
            }
            JsonNode contentNode = node.get("content");
            if (contentNode == null) {
                contentNode = NullNode.getInstance();
            }
            return new TextPrompt(keyNode.asText(), contentNode);
        }
    }
}
