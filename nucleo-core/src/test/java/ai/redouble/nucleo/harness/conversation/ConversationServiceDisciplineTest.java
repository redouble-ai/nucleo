/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.nucleo.harness.conversation;

import ai.redouble.nucleo.harness.*;
import ai.redouble.nucleo.harness.errors.*;
import ai.redouble.nucleo.harness.models.*;
import ai.redouble.nucleo.tools.*;
import ai.redouble.nucleo.tools.thinking.*;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the durability discipline of {@link ConversationService}: the FIRST save of a
 * minted conversation CREATES the durable record exactly once and every later save is a
 * plain update; a temporary (jobId-keyed) conversation never reaches the store; and a
 * second thinker asking for an owned conversation blocks until the owner releases -
 * the exclusive-ownership model's whole point.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-08-31)
 */
public class ConversationServiceDisciplineTest {
    private static final String USER = "discipline-test-user";

    @AfterEach
    void detachStore() {
        ConversationService.getInstance().setPersistentStore(null);
    }

    /** Store that counts the read and write paths and refuses everything it should never see. */
    static final class CountingStore implements PersistentStore {
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicReference<Identifiable> lastLoadCaller = new AtomicReference<>();

        @Override
        public void createContext(ConversationContext context, Thinker<?, ?> thinker) {
            creates.incrementAndGet();
        }

        @Override
        public void save(ConversationPersistenceSnapshot snapshot, String user) {
            updates.incrementAndGet();
        }

        /** Snapshot served to every durable load; null = store miss. */
        volatile ConversationPersistenceSnapshot canned;

        @Override
        public ConversationPersistenceSnapshot load(String conversationId, String user, Identifiable caller) {
            loads.incrementAndGet();
            lastLoadCaller.set(caller);
            return canned;
        }

        @Override
        public boolean exists(String conversationId, String user) {
            return false;
        }

        @Override
        public boolean delete(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public Long getLastModified(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public List<String> findContexts(ContextQuery query, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public void saveWithMetadata(ConversationPersistenceSnapshot snapshot, Map<String, String> metadata, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public Map<String, String> getMetadata(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public boolean archive(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public boolean restore(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public boolean isArchived(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public List<Instant> getVersionHistory(String conversationId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public ConversationPersistenceSnapshot loadVersion(String conversationId, Instant versionTime, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public int backup(String backupId, String user) {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }

        @Override
        public Map<String, Object> getStats() {
            throw new UnsupportedOperationException("not part of the discipline under test");
        }
    }

    private static Identifiable root() {
        return Job.workflow(USER, "discipline-test");
    }

    private static ConversationContext obtain(String conversationId, Thinker<?, ?> thinker) {
        return ConversationService.getInstance()
                                  .obtainConversation(conversationId, thinker, StringResponseHandler.instance, false)
                                  .join();
    }

    @Test
    void firstSaveOfAMintedConversationCreates_everyLaterSaveUpdates() {
        CountingStore store = new CountingStore();
        ConversationService.getInstance().setPersistentStore(store);
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        ConversationContext context = obtain(minted, thinker);
        // A durable chat surface marks its conversation persistent (ChatThinker does this);
        // temporary gates WHETHER to persist, minted-ness picks create-vs-update.
        context.setTemporary(false);
        try {
            ConversationService.getInstance().saveConversation(context, thinker);
            ConversationService.getInstance().saveConversation(context, thinker);
            ConversationService.getInstance().saveConversation(context, thinker);
            assertEquals(1, store.creates.get(), "the durable record is created exactly once, at the first boundary");
            assertEquals(2, store.updates.get(), "every subsequent boundary is a plain update, no reads");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
        assertEquals(1, store.creates.get(), "release transfers ownership only - durability happened at the boundaries");
        assertEquals(2, store.updates.get(), "and eviction persists nothing either");
    }

    @Test
    void aRehydratedConversationTakesTheAdoptingThinkersCurrentDeclarations() {
        CountingStore store = new CountingStore();
        // The stored conversation declared a different seat than the adopting thinker's
        ConversationContext stored = new ConversationContext("adopt-declarations-" + java.util.UUID.randomUUID());
        stored.setGrade(Grade.MEGA);
        stored.setDepth(Depth.ULTRA_THOROUGH);
        store.canned = stored.toSnapshot();
        ConversationService.getInstance().setPersistentStore(store);
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        ConversationContext adopted = obtain(minted, thinker);
        try {
            assertEquals(thinker.getGrade(), adopted.getGrade(),
                    "the adopting thinker's CURRENT grade wins over what was stored");
            assertEquals(thinker.getDepth(), adopted.getDepth(), "and its current depth");
            assertEquals(thinker.getOutputDeclaration(), adopted.getOutputDeclaration(), "and its current output declaration");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    @Test
    void acquisitionTimesOutTyped_whenTheOwnerNeverReleases() {
        ProbeThinker owner = new ProbeThinker(root());
        String minted = owner.mintConversationId();
        obtain(minted, owner);
        ProbeThinker challenger = new ProbeThinker(root());
        challenger.setConversationId(minted);
        long normalTimeout = ConversationService.conversationTimeoutMs;
        ConversationService.conversationTimeoutMs = 100;
        try {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> obtain(minted, challenger),
                    "an owner that never releases starves the challenger at the timeout");
            assertInstanceOf(SystemException.class, failure.getCause(),
                    "the timeout is the same typed refusal as the mustExist miss, never a raw wrapper");
            assertTrue(failure.getCause().getMessage().contains(owner.getJobId()),
                    "the refusal names the owner: " + failure.getCause().getMessage());
        }
        finally {
            ConversationService.conversationTimeoutMs = normalTimeout;
            ConversationService.getInstance().release(owner);
        }
    }

    @Test
    void theSweepEvictsExpiredUnownedHolders() throws Exception {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        obtain(minted, thinker);
        ConversationService.getInstance().release(thinker, Duration.ofMillis(1));
        Thread.sleep(10);
        ConversationService.getInstance().sweepExpired();
        CompletionException failure = assertThrows(CompletionException.class, () -> {
            ProbeThinker resumer = new ProbeThinker(root());
            resumer.setConversationId(minted);
            ConversationService.getInstance()
                               .obtainConversation(minted, resumer, StringResponseHandler.instance, true)
                               .join();
        }, "the expired holder left memory, and with no store the resume finds nothing");
        assertInstanceOf(SystemException.class, failure.getCause());
    }

    @Test
    void releaseAllForJobEvictsWhatADeadJobStillOwned() {
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        obtain(minted, thinker);
        ConversationService.getInstance().releaseAllForJob(thinker.getJobId());
        assertNull(ConversationService.getInstance().owningThinker(minted, USER),
                "the safety net released the dead job's conversation and evicted it from memory");
    }

    @Test
    void temporaryConversationsNeverReachTheStore() {
        CountingStore store = new CountingStore();
        ConversationService.getInstance().setPersistentStore(store);
        ProbeThinker thinker = new ProbeThinker(root());
        ConversationContext context = obtain(thinker.getConversationId(), thinker);
        try {
            ConversationService.getInstance().saveConversation(context, thinker);
            assertEquals(0, store.creates.get(), "a jobId-keyed conversation is temporary by contract");
            assertEquals(0, store.updates.get());
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    /**
     * The read side of the temporary contract: a jobId-keyed conversation can never be in
     * a store (the save side above refuses to put it there), so obtaining one must not
     * consult persistence at all - on a durable store every such consult is a dispatched
     * read, and lanes run thousands of thinkers.
     */
    @Test
    void obtainingATemporaryDefaultConversationNeverConsultsTheStore() {
        CountingStore store = new CountingStore();
        ConversationService.getInstance().setPersistentStore(store);
        ProbeThinker thinker = new ProbeThinker(root());
        obtain(thinker.getConversationId(), thinker);
        try {
            assertEquals(0, store.loads.get(), "a jobId-keyed obtain is a guaranteed store miss and must not be attempted");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    /**
     * A durable id does consult the store, and the read runs on the obtaining thinker's
     * behalf: the store parents its read jobs under the caller, so the load lands inside
     * the workflow that needed it instead of minting a root of its own.
     */
    @Test
    void obtainingADurableConversationConsultsTheStoreOnTheThinkersBehalf() {
        CountingStore store = new CountingStore();
        ConversationService.getInstance().setPersistentStore(store);
        ProbeThinker thinker = new ProbeThinker(root());
        String minted = thinker.mintConversationId();
        obtain(minted, thinker);
        try {
            assertEquals(1, store.loads.get(), "a durable id resolves through the store");
            assertSame(thinker, store.lastLoadCaller.get(), "the read runs on the obtaining thinker's behalf");
        }
        finally {
            ConversationService.getInstance().release(thinker);
        }
    }

    @Test
    void aSecondThinkerBlocksUntilTheOwnerReleases() throws Exception {
        ProbeThinker owner = new ProbeThinker(root());
        String minted = owner.mintConversationId();
        ConversationContext held = obtain(minted, owner);
        assertNotNull(held);

        ProbeThinker challenger = new ProbeThinker(root());
        challenger.setConversationId(minted);
        CountDownLatch acquired = new CountDownLatch(1);
        AtomicReference<ConversationContext> challengerContext = new AtomicReference<>();
        Thread contender = Thread.ofVirtual().start(() -> {
            challengerContext.set(obtain(minted, challenger));
            acquired.countDown();
        });

        assertFalse(acquired.await(400, TimeUnit.MILLISECONDS),
                "while the owner holds the conversation, the challenger waits");
        ConversationService.getInstance().release(owner);
        assertTrue(acquired.await(5, TimeUnit.SECONDS),
                "the release hands the conversation to the waiting challenger");
        assertEquals(minted, challengerContext.get().getConversationId());
        ConversationService.getInstance().release(challenger);
        contender.join(TimeUnit.SECONDS.toMillis(2));
    }

    private static final class ProbeThinker extends AbstractThinker<VoidThinkerInput, VoidThinkerOutput> {
        ProbeThinker(Identifiable parent) {
            super(parent, new ThinkerDeclaration(Grade.SMALL, OutputSize.COMPACT));
        }

        @Override
        protected List<Class<? extends Tool>> declareDefaultTools() {
            return List.of();
        }

        @Override
        public Depth getDepth() {
            return Depth.STANDARD;
        }

        @Override
        protected void runThinkingLoop(ConversationContext conversation, JobContext<VoidThinkerOutput> context) {
            // Discipline is exercised against ConversationService directly.
        }

        @Override
        protected VoidThinkerOutput getResult() {
            return null;
        }
    }
}
