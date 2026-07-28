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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Span-style transactions, as a host framework with a begin/commit API uses them (one consumer has
 * ~850 such call sites, which is why adapting underneath beats rewriting them).
 */
class WriteTransactionTest {

    @Test
    void beginCommitPersistsAndReleasesTheLock() {
        Path dir = TestStores.newDir("tx-commit");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            WriteTransaction tx = db.beginWrite();
            assertTrue(db.isWriteActive());
            tx.context().edit(root.entries);
            root.entries.put("k", "v");
            tx.commit();

            assertFalse(db.isWriteActive(), "the write lock is released on commit");
            assertFalse(tx.isActive());
            // A later read proves the lock really was released, not just flagged.
            assertEquals("v", db.read(() -> root.entries.get("k")));
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            assertEquals("v", db.<TestRoot>root().entries.get("k"), "committed to disk");
        }
    }

    @Test
    void rollbackRestoresStateAndPersistsNothing() {
        Path dir = TestStores.newDir("tx-rollback");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("keep", "me");
            });

            WriteTransaction tx = db.beginWrite();
            tx.context().edit(root.entries);
            root.entries.put("doomed", "x");
            root.entries.remove("keep");
            tx.rollback();

            assertEquals("me", root.entries.get("keep"), "restored in memory");
            assertFalse(root.entries.containsKey("doomed"));
            assertFalse(db.isWriteActive());
        }
    }

    @Test
    void tryWithResourcesRollsBackAnUnfinishedTransaction() {
        Path dir = TestStores.newDir("tx-leak");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertThrows(RuntimeException.class, () -> {
                try (WriteTransaction tx = db.beginWrite()) {
                    tx.context().edit(root.entries);
                    root.entries.put("doomed", "x");
                    throw new RuntimeException("boom");
                }
            });
            assertTrue(root.entries.isEmpty(), "close() rolled the transaction back");
            assertFalse(db.isWriteActive(), "and released the lock, so the store is not wedged");
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("after", "ok");
            });
            assertEquals("ok", root.entries.get("after"));
        }
    }

    @Test
    void nestedTransactionJoinsTheOuterOneAndCommitsOnce() {
        Path dir = TestStores.newDir("tx-nested");
        AtomicLong commits = new AtomicLong();
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            db.addCommitListener((seq, count) -> commits.incrementAndGet());
            TestRoot root = db.root();

            WriteTransaction outer = db.beginWrite();
            outer.context().edit(root.entries);
            root.entries.put("outer", "1");

            WriteTransaction inner = db.beginWrite();
            assertFalse(inner.isOutermost(), "the inner transaction joined the outer one");
            inner.context().edit(root.entries);
            root.entries.put("inner", "2");
            inner.commit();
            assertTrue(db.isWriteActive(), "committing the inner must not end the outer");

            outer.commit();
            assertEquals(1, commits.get(), "exactly one commit for the whole nest");
            assertEquals("2", root.entries.get("inner"));
        }
    }

    @Test
    void nestedRollbackPoisonsTheOuterCommit() {
        Path dir = TestStores.newDir("tx-nested-rollback");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            WriteTransaction outer = db.beginWrite();
            outer.context().edit(root.entries);
            root.entries.put("outer", "1");

            WriteTransaction inner = db.beginWrite();
            inner.rollback();

            assertThrows(IllegalStateException.class, outer::commit,
                    "an outer commit must not silently persist work a nested rollback disowned");
            assertTrue(root.entries.isEmpty(), "and the whole transaction rolled back");
            assertFalse(db.isWriteActive());
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            assertTrue(db.<TestRoot>root().entries.isEmpty(), "nothing reached disk");
        }
    }

    @Test
    void blocksAndTransactionsInteroperate() {
        Path dir = TestStores.newDir("tx-interop");
        AtomicLong commits = new AtomicLong();
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            db.addCommitListener((seq, count) -> commits.incrementAndGet());
            TestRoot root = db.root();

            // A framework's begin/commit span with library-style blocks called inside it.
            WriteTransaction tx = db.beginWrite();
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("from-block", "1");
            });
            tx.context().edit(root.entries);
            root.entries.put("from-span", "2");
            tx.commit();

            assertEquals(1, commits.get(), "the nested block joined the span's single commit");
            assertEquals("1", root.entries.get("from-block"));
            assertEquals("2", root.entries.get("from-span"));
        }
    }

    @Test
    void doubleFinishIsRejected() {
        Path dir = TestStores.newDir("tx-double");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            WriteTransaction tx = db.beginWrite();
            tx.commit();
            assertThrows(IllegalStateException.class, tx::commit);
            assertThrows(IllegalStateException.class, tx::rollback);
            tx.close();      // must stay harmless
        }
    }

    @Test
    void concurrentTransactionsSerialize() throws Exception {
        Path dir = TestStores.newDir("tx-concurrent");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            int threads = 8;
            int perThread = 25;
            java.util.List<Thread> workers = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                workers.add(Thread.ofVirtual().start(() -> {
                    for (int i = 0; i < perThread; i++) {
                        try (WriteTransaction tx = db.beginWrite()) {
                            tx.context().edit(root);
                            root.a++;
                            root.b++;
                            tx.commit();
                        }
                    }
                }));
            }
            for (Thread worker : workers) {
                worker.join(60_000);
            }
            assertEquals(threads * perThread, root.a, "no increment lost");
            assertEquals(root.a, root.b);
        }
    }
}
