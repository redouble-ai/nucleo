/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.models;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.*;
import ch.qos.logback.core.read.*;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalog names no spec class: an entry is loaded into every spec class on the classpath,
 * each says how well it fits ({@link AbstractModelSpec#match()}), and the highest answer is
 * kept. On equal answers the class nearer to {@link AbstractModelSpec} is kept, so a
 * superclass over its subclass, and of two at the same distance the one whose name sorts
 * first. A property an entry states that its class has no field for is
 * named in one warning of the runtime's own load, unless the class answered
 * {@link AbstractModelSpec#DEFINITELY_MINE}.
 *
 * <p>The spec classes below are named in this module's test
 * {@code META-INF/services/ai.redouble.nucleo.harness.models.AbstractModelSpec}. Each claims
 * only an entry that states a property of its own, so no other test's catalog meets them.
 *
 * @author Andrey Santrosyan
 * @since 0.1.3 (2026-11-14)
 */
public class SpecClassSelectionTest {
    /** Claims an entry that states {@code test_marker}, one step narrower than its parent. */
    public static class Marked extends StandardModelSpec {
        private String testMarker;

        public String getTestMarker() {return testMarker;}

        public void setTestMarker(String testMarker) {this.testMarker = testMarker;}

        @Override
        public int match() {
            return testMarker != null ? super.match() + 1 : 0;
        }
    }

    /** A subclass that states nothing of its own: it inherits its parent's answer. */
    public static class MarkedSilent extends Marked {
    }

    /** One step narrower than {@link Marked} where the entry also states {@code test_second_marker}; its parent's answer elsewhere. */
    public static class MarkedTwice extends Marked {
        private String testSecondMarker;

        public String getTestSecondMarker() {return testSecondMarker;}

        public void setTestSecondMarker(String testSecondMarker) {this.testSecondMarker = testSecondMarker;}

        @Override
        public int match() {
            return testSecondMarker != null ? super.match() + 1 : super.match();
        }
    }

    /** Written for the entry that states {@code test_owned}. */
    public static class Owner extends StandardModelSpec {
        private Boolean testOwned;

        public Boolean getTestOwned() {return testOwned;}

        public void setTestOwned(Boolean testOwned) {this.testOwned = testOwned;}

        @Override
        public int match() {
            return Boolean.TRUE.equals(testOwned) ? DEFINITELY_MINE : 0;
        }
    }

    /** Two unrelated classes that fit an entry stating {@code test_tie} equally. */
    public static class TieB extends StandardModelSpec {
        private String testTie;

        public String getTestTie() {return testTie;}

        public void setTestTie(String testTie) {this.testTie = testTie;}

        @Override
        public int match() {
            return testTie != null ? super.match() + 1 : 0;
        }
    }

    public static class TieA extends StandardModelSpec {
        private String testTie;

        public String getTestTie() {return testTie;}

        public void setTestTie(String testTie) {this.testTie = testTie;}

        @Override
        public int match() {
            return testTie != null ? super.match() + 1 : 0;
        }
    }

    private static String entry(String id, String extra) {
        return "{ \"id\": \"" + id + "\", \"identity\": \"" + id + "\", \"grade\": \"SMALL\", \"provider_key\": \"fake-llm\","
                + " \"wire_model_id\": \"wire-" + id + "\", \"max_context_tokens\": 1000, \"max_output_tokens\": 100, \"tpm\": 5000" + extra + " }";
    }

    private static Class<?> classOf(String extra) {
        JsonModelsBackend catalog = new JsonModelsBackend(List.of(new JsonModelsBackend.Layer("test", "{ \"models\": [" + entry("subject", extra) + "] }", false)));
        return catalog.spec("subject").getClass();
    }

    @Test
    void anEntryNoClassClaimsIsAStandardSpec() {
        assertEquals(StandardModelSpec.class, classOf(""), "the standard class fits every entry, and nothing else answered above zero");
    }

    @Test
    void aClassThatRecognizesTheEntryIsKeptOverTheStandardOne() {
        assertEquals(Marked.class, classOf(", \"test_marker\": \"x\""));
    }

    @Test
    void aSubclassThatInheritsItsParentsAnswerIsNeverKeptOverItsParent() {
        // MarkedSilent answers what Marked answers on every entry; the equal answer goes to the superclass
        assertEquals(Marked.class, classOf(", \"test_marker\": \"x\""), "MarkedSilent states nothing of its own");
    }

    @Test
    void aSubclassIsKeptOverItsParentOnlyWhereItsOwnCheckPasses() {
        assertEquals(MarkedTwice.class, classOf(", \"test_marker\": \"x\", \"test_second_marker\": \"y\""), "one step narrower than its parent");
        assertEquals(Marked.class, classOf(", \"test_marker\": \"x\""), "where it returns its parent's answer, its parent is kept");
        assertEquals(StandardModelSpec.class, classOf(", \"test_second_marker\": \"y\""), "its parent refuses the entry, and so does it");
    }

    @Test
    void aClassWrittenForTheEntryIsKeptOverAnyOtherAnswer() {
        assertEquals(Owner.class, classOf(", \"test_owned\": true, \"test_marker\": \"x\", \"test_second_marker\": \"y\""));
        assertEquals(MarkedTwice.class, classOf(", \"test_owned\": false, \"test_marker\": \"x\", \"test_second_marker\": \"y\""), "an entry it was not written for is not its own");
    }

    @Test
    void unrelatedClassesWithEqualAnswersResolveToTheSameClassOnEveryLoad() {
        for (int load = 0; load < 5; load++) {
            assertEquals(TieA.class, classOf(", \"test_tie\": \"x\""), "the class whose name sorts first, load " + load);
        }
    }

    @Test
    void theClassesAreOfferedSuperclassFirstThenByName() {
        List<Class<? extends AbstractModelSpec>> classes = SpecClasses.find();
        assertEquals(StandardModelSpec.class, classes.get(0), "the class nearest to AbstractModelSpec");
        assertTrue(classes.indexOf(Marked.class) < classes.indexOf(MarkedSilent.class), "a superclass before its subclass: " + classes);
        assertTrue(classes.indexOf(Marked.class) < classes.indexOf(MarkedTwice.class), classes.toString());
        assertTrue(classes.indexOf(TieA.class) < classes.indexOf(TieB.class), "at the same distance, by name: " + classes);
        assertEquals(classes, SpecClasses.find(), "the same order every time it is read");
    }

    /** A runtime catalog over {@code /models-unread.json}, the load that warns. */
    static class RuntimeCatalog extends JsonModelsBackend {
        RuntimeCatalog() {
            super("/models-unread.json");
        }
    }

    @Test
    void aPropertyNoFieldReceivesIsNamedOnceByTheRuntimesLoad_unlessTheClassWasWrittenForTheEntry() {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonModelsBackend.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JsonModelsBackend catalog = new RuntimeCatalog();
            assertEquals(Owner.class, catalog.spec("owned").getClass());
            List<String> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, warnings.size(), warnings.toString());
            assertTrue(warnings.get(0).contains("mystery_rate=[plain, marked]"), "the property and the entries that state it, in catalog order: " + warnings.get(0));
            assertFalse(warnings.get(0).contains("owned"), "an entry whose class was written for it is not named: " + warnings.get(0));
            assertFalse(warnings.get(0).contains("test_marker") || warnings.get(0).contains("platform"),
                    "a property the class has a field for, and one the loader reads itself, are read: " + warnings.get(0));
        }
        finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void aViewOverACatalogWithUnreadPropertiesSaysNothing() {
        Logger logger = (Logger) LoggerFactory.getLogger(JsonModelsBackend.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(StandardModelSpec.class, classOf(", \"mystery_rate\": 3.5"), "the entry loads, the property passed over");
            assertTrue(appender.list.stream().noneMatch(e -> e.getLevel() == Level.WARN), "only the runtime's own load warns");
        }
        finally {
            logger.detachAppender(appender);
        }
    }
}
