/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.prompt;

import ai.redouble.nucleo.prompt.sources.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cache behavior: static sources are cached after first produce; dynamic sources
 * are re-produced each time; {@link Prompts#replace} and {@link Prompts#setGlobalBackend}
 * invalidate the cache without exposing the "new source + stale cache" race - including
 * the produce-side half, where a substitution landing mid-produce must evict the stale
 * prompt that produce publishes after it. Also pins the code-default registration
 * ergonomics ({@link Prompts#of(String, String)} last-write-wins,
 * {@link Prompts#bindStaticDefault} first-bind-wins) and {@link Prompts#warmCache}.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-04-21)
 */
public class PromptsCacheTest {
    @BeforeEach
    void reset() {
        Prompts.resetAll();
    }

    /** The registries are process-wide and the fork runs other classes after this one: nothing registered here outlives the test. */
    @AfterEach
    void restore() {
        Prompts.resetAll();
    }

    @Test
    void staticSourceProducedOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PromptSource source = new StaticTextSource("once") {
            @Override
            public JsonNode produce(String key) {
                calls.incrementAndGet();
                return super.produce(key);
            }
        };
        Prompts.replace("k", source);
        Prompts.produce("k");
        Prompts.produce("k");
        Prompts.produce("k");
        assertEquals(1, calls.get());
    }

    @Test
    void dynamicSourceProducedEveryTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PromptSource source = key -> {
            int n = calls.incrementAndGet();
            return TextNode.valueOf("call-" + n);
        };
        Prompts.replace("k", source);
        assertEquals("call-1", Prompts.produce("k").content().asText());
        assertEquals("call-2", Prompts.produce("k").content().asText());
        assertEquals("call-3", Prompts.produce("k").content().asText());
    }

    @Test
    void replaceInvalidatesCache() throws Exception {
        Prompts.replace("k", new StaticTextSource("v1"));
        assertEquals("v1", Prompts.produce("k").content().asText());
        Prompts.replace("k", new StaticTextSource("v2"));
        assertEquals("v2", Prompts.produce("k").content().asText());
    }

    @Test
    void aSubstitutionLandingMidProduceIsNeverShadowedByTheStaleCache() throws Exception {
        // The interleaving the invalidation ordering alone cannot cover: produce() has already
        // resolved the old static source when replace() publishes a new one, so replace's
        // CACHE.remove ran BEFORE the stale prompt was cached. The produce side must re-check.
        class SelfReplacingSource extends StaticTextSource {
            SelfReplacingSource() {
                super("stale");
            }

            @Override
            public JsonNode produce(String key) {
                Prompts.replace("k", new StaticTextSource("fresh"));
                return super.produce(key);
            }
        }
        Prompts.replace("k", new SelfReplacingSource());
        assertEquals("stale", Prompts.produce("k").content().asText(), "the in-flight produce returns what it produced");
        assertEquals("fresh", Prompts.produce("k").content().asText(),
                "the substitution that landed mid-produce wins every later call - a stale prompt"
                        + " must never stay cached over a fresh source");
    }

    @Test
    void ofWithAKeyReRegistersLastWriteWins() throws Exception {
        Prompts.of("k", "v1");
        Prompts.of("k", "v2");
        assertEquals("v2", Prompts.produce("k").content().asText(),
                "the ergonomic code-default entry point replaces a prior registration");
    }

    @Test
    void bindStaticDefaultReusesTheExistingRegistration() throws Exception {
        Prompt first = Prompts.bindStaticDefault("k", "v1");
        assertEquals("v1", first.content().asText());
        Prompt second = Prompts.bindStaticDefault("k", "v2");
        assertSame(first, second,
                "a later bind is a no-op - the SAME cached prompt comes back, proving the"
                        + " registration was reused and the cache never invalidated, so"
                        + " per-construction binds from thinkers stay free");
    }

    @Test
    void warmCacheProducesEveryStaticDefaultOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Prompts.registerDefault("warm.static", new StaticTextSource("warmed") {
            @Override
            public JsonNode produce(String key) {
                calls.incrementAndGet();
                return super.produce(key);
            }
        });
        Prompts.warmCache();
        assertEquals(1, calls.get(), "bootstrap produced the static default");
        assertEquals("warmed", Prompts.produce("warm.static").content().asText());
        assertEquals(1, calls.get(), "later produces hit the warmed cache");
    }

    @Test
    void setGlobalBackendClearsAllCachedEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Prompts.registerDefault("k1", new StaticTextSource("static-1") {
            @Override
            public JsonNode produce(String key) {
                calls.incrementAndGet();
                return super.produce(key);
            }
        });
        assertEquals("static-1", Prompts.produce("k1").content().asText());
        assertEquals(1, calls.get(), "the static default is cached");
        Prompts.setGlobalBackend(key -> TextNode.valueOf("backend:" + key));
        assertEquals("backend:k1", Prompts.produce("k1").content().asText(),
                "the backend answers - a surviving cache entry would have shadowed it");
        Prompts.clearGlobalBackend();
        assertEquals("static-1", Prompts.produce("k1").content().asText());
        assertEquals(2, calls.get(),
                "the default RE-produced after the backend era - its cache entry did not survive");
    }
}
