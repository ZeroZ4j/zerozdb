/*
 * Copyright 2026 Franz Schöning
 * Project: https://www.zeroz4j.com
 * Author: Franz Schöning - Principal Enterprise Architect (https://www.franzschoning.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zeroz4j.db;

import com.zeroz4j.db.net.SnapshotQuery;
import com.zeroz4j.db.net.ZeroZDbClient;
import com.zeroz4j.db.net.ZeroZDbServer;
import org.eclipse.serializer.reference.Lazy;
import org.eclipse.serializer.reference.LazyReferenceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How EclipseStore's {@link Lazy} behaves under this engine. The documentation names {@code Lazy}
 * as the answer to the central limitation — a store must fit the heap — so what it does with
 * before-images, enlistment, indexes, eviction and replicas is part of this library's contract
 * whether or not the library wrote the code.
 * <p>
 * The recurring answer: {@code Lazy} is invisible to everything ZeroZ DB does by reflection over an
 * object's own fields. A holder is one reference-shaped field, so the engine sees a reference that
 * did not change, no matter what happened to the collection behind it.
 */
class LazyTest {

    private static final String SONG = "s1";

    /** A store holding one song whose entries sit behind a {@code Lazy} reference. */
    private static void seed(Path dir, int entryCount) {
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazyRoot root = db.root();
            db.write(ctx -> {
                LazySong song = new LazySong("Take Five");
                for (int i = 0; i < entryCount; i++) {
                    song.entries().add("e" + i);
                }
                root.songs.put(SONG, song);
                ctx.store(root.songs);
                ctx.store(song);
            });
        }
    }

    private static LazySong songIn(ZeroZDb db) {
        LazyRoot root = db.root();
        return root.songs.get(SONG);
    }

    // ---------------------------------------------------------------- the premise

    /** The premise everything else rests on: reopening does not drag the collection in. */
    @Test
    void aStoredLazyCollectionStaysOnDiskUntilItIsAskedFor() {
        Path dir = TestStores.newDir("lazy-basic");
        seed(dir, 5);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            assertEquals("Take Five", song.title, "the identifying part is resident");
            assertFalse(song.entries.isLoaded(), "the collection must not load just by reopening");
            assertEquals(5, song.entries().size(), "and it loads on first use");
            assertTrue(song.entries.isLoaded());
        }
    }

    // ---------------------------------------------------------------- enlistment

    /**
     * Enlisting does not cascade, and a {@code Lazy} makes that rule sharper rather than softer:
     * the owner's own record only holds the reference, which did not change. Enlist the
     * materialised collection.
     */
    @Test
    void storingTheOwnerDoesNotPersistAChangeMadeBehindItsLazyReference() {
        Path dir = TestStores.newDir("lazy-enlist");
        seed(dir, 1);

        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            db.write(ctx -> {
                song.entries().add("owner-enlisted");
                ctx.store(song);                       // the owner, not the collection
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            assertEquals(1, songIn(db).entries().size(),
                    "enlisting the owner must not be mistaken for enlisting what its Lazy "
                            + "points at — the change is lost exactly as with any other "
                            + "un-enlisted nesting level");
        }

        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            db.write(ctx -> {
                song.entries().add("collection-enlisted");
                ctx.store(song.entries());             // the collection itself
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            assertEquals(2, songIn(db).entries().size(),
                    "enlisting the materialised collection is what persists it");
        }
    }

    // ---------------------------------------------------------------- rollback

    /**
     * A before-image of the owner records its flat fields, and the {@code Lazy} field is one
     * reference that is the same object before and after. So rollback restores nothing, and the
     * failed block's mutation survives in memory — the one place where a {@code Lazy} silently
     * weakens a guarantee the engine otherwise gives.
     */
    @Test
    void rollbackDoesNotRestoreACollectionBehindALazyReference() {
        Path dir = TestStores.newDir("lazy-rollback-owner");
        seed(dir, 1);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            song.entries();

            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.edit(song);
                song.entries().add("doomed");
                throw new RuntimeException("boom");
            }));

            assertTrue(song.entries().contains("doomed"),
                    "the owner's snapshot holds the Lazy holder, not the collection behind it, "
                            + "so there is nothing for rollback to put back");
        }
    }

    /** Enlisting the materialised collection restores the engine's normal rollback fidelity. */
    @Test
    void rollbackRestoresTheCollectionWhenTheMaterialisedListIsEnlisted() {
        Path dir = TestStores.newDir("lazy-rollback-list");
        seed(dir, 1);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);

            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.edit(song.entries());
                song.entries().add("doomed");
                throw new RuntimeException("boom");
            }));

            assertFalse(song.entries().contains("doomed"),
                    "a Map or Collection before-image snapshots contents, so this one is restored");
        }
    }

    // ---------------------------------------------------------------- indexes

    /** An index needs something to iterate, and a holder is neither a Map nor a Collection. */
    @Test
    void anIndexSourceCannotBeALazyReferenceItself() {
        Path dir = TestStores.newDir("lazy-index-holder");
        seed(dir, 3);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> db.index("entries", String.class, () -> song.entries, e -> e));
            assertTrue(failure.getMessage().contains("Map or Collection"), failure.getMessage());
        }
    }

    /**
     * The pattern that keeps both: index the owners, which are small, and leave each owner's
     * unbounded collection on disk. This is the shape to reach for.
     */
    @Test
    void indexingTheOwnersLeavesTheirLazyCollectionsOnDisk() {
        Path dir = TestStores.newDir("lazy-index-owners");
        seed(dir, 3);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazyRoot root = db.root();
            Index<String, LazySong> byTitle =
                    db.index("by-title", LazySong.class, () -> root.songs, s -> s.title);

            assertEquals(1, byTitle.get("Take Five").size());
            assertFalse(songIn(db).entries.isLoaded(),
                    "indexing the owners must not pull their Lazy collections into the heap");
        }
    }

    /**
     * The opposite, stated so nobody has to discover it: registering an index over a materialised
     * {@code Lazy} scans it, and every commit that enlists the source scans it again. The
     * collection is resident from then on, which is the whole cost the Lazy was avoiding.
     */
    @Test
    void anIndexOverAMaterialisedLazySourceForcesItResident() {
        Path dir = TestStores.newDir("lazy-index-source");
        seed(dir, 3);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            assertFalse(song.entries.isLoaded());

            db.index("entries", String.class, song::entries, e -> e);

            assertTrue(song.entries.isLoaded(),
                    "registering an index scans its source, so the source must be in memory");
        }
    }

    // ---------------------------------------------------------------- eviction

    /**
     * Worth knowing before building eviction by hand: EclipseStore starts its global
     * {@code LazyReferenceManager} when the storage manager starts, so a timer is already clearing
     * untouched references under this engine with no configuration at all.
     */
    @Test
    void theLazyReferenceManagerRunsWithoutTheApplicationStartingIt() {
        Path dir = TestStores.newDir("lazy-manager");
        seed(dir, 1);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            assertTrue(db.root() instanceof LazyRoot);
            assertTrue(LazyReferenceManager.get().isRunning(),
                    "the application never called start(); EclipseStore did it when the store "
                            + "was opened");
        }
    }

    /** Clearing frees the collection without losing it: the next call loads it again. */
    @Test
    void clearingALazyReferenceFreesTheCollectionAndItReloadsOnNextUse() {
        Path dir = TestStores.newDir("lazy-clear");
        seed(dir, 4);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);
            assertEquals(4, song.entries().size());
            assertTrue(song.entries.isLoaded());

            song.entries.clear();

            assertFalse(song.entries.isLoaded(), "the collection is released");
            assertEquals(4, song.entries().size(), "cleared is not lost; it loads again");
        }
    }

    /**
     * The trap for anyone clearing references on a schedule of their own — logout, say. A holder
     * whose contents have never been committed has nowhere to reload from, and clearing it is
     * refused rather than silently discarding unsaved work.
     */
    @Test
    void clearingALazyReferenceThatWasNeverStoredIsRefused() {
        Lazy<List<String>> fresh = Lazy.Reference(new ArrayList<>(List.of("never committed")));
        IllegalStateException failure = assertThrows(IllegalStateException.class, fresh::clear);
        assertTrue(failure.getMessage().toLowerCase().contains("unstored"), failure.getMessage());
    }

    // ---------------------------------------------------------------- read and write blocks

    /** Loading a reference is disk I/O, and it is allowed in both kinds of block. */
    @Test
    void aLazyReferenceLoadsInsideBothReadBlocksAndWriteBlocks() {
        Path dir = TestStores.newDir("lazy-blocks");
        seed(dir, 3);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir)) {
            LazySong song = songIn(db);

            assertEquals(3, (int) db.read(() -> song.entries().size()),
                    "loading inside a read block is fine");

            song.entries.clear();
            db.write(ctx -> {
                assertEquals(3, song.entries().size(),
                        "and inside a write block — at the cost of holding the write lock "
                                + "across the read from disk");
                ctx.store(song.entries());
            });
        }
    }

    // ---------------------------------------------------------------- replicas

    /**
     * The root fact behind everything else on this page: the network layer's wire format is the
     * standalone serializer, which has no storage behind it — so it cannot write a reference whose
     * whole meaning is "ask the storage for object 12345". A {@code Lazy} therefore cannot cross
     * the wire in either direction, loaded or not.
     */
    @Test
    void aLazyReferenceCannotBeSerializedForTheWire() throws Exception {
        LazySong song = new LazySong("Take Five");
        song.entries().add("e0");

        try (org.eclipse.serializer.Serializer<byte[]> serializer =
                     org.eclipse.serializer.TypedSerializer.Bytes()) {
            Throwable failure = assertThrows(Throwable.class, () -> serializer.serialize(song));
            assertTrue(String.valueOf(failure.getMessage()).toLowerCase().contains("lazy"),
                    "expected a refusal naming the lazy reference: " + failure);
        }
    }

    /**
     * The consequence for replication, and it is worse than "unsupported": the reply is serialized
     * outside the server's per-request error handling, so the connection dies and the caller gets
     * an {@link java.io.EOFException} rather than anything naming a {@code Lazy}.
     */
    @Test
    void aReplicaSnapshotOfAGraphContainingALazyReferenceFailsUndiagnosably() throws Exception {
        Path dir = TestStores.newDir("lazy-replica");
        seed(dir, 3);
        try (ZeroZDb db = ZeroZDb.open(new LazyRoot(), dir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "default")) {

            songIn(db).entries();                       // even fully loaded on the owner

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> client.query("main", new SnapshotQuery()));
            assertTrue(failure.getCause() instanceof java.io.EOFException,
                    "the connection drops instead of reporting why: " + failure.getCause());
        }
    }
}
