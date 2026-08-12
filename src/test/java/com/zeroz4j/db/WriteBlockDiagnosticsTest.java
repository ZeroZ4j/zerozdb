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
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a store says when a write-block is open, and especially when one was leaked. Reported from
 * a consumer application in August 2026: {@code close()} took the write lock with an unbounded
 * {@code lock()}, so a leaked transaction on a sleeping thread parked the closing thread for ever
 * with no log line, no exception and no way to ask who held it. Diagnosing that took two thread
 * dumps and a read of the engine's source; it should take one sentence.
 */
class WriteBlockDiagnosticsTest {

    @Test
    void closeGivesUpAndNamesTheThreadHoldingALeakedBlock() throws Exception {
        Path dir = TestStores.newDir("close-leaked");
        ZeroZDb db = ZeroZDb.open(new TestRoot(), dir);
        try (HeldBlock held = new HeldBlock(db, "leaky-worker")) {
            StoreBusyException busy = assertThrows(StoreBusyException.class,
                    () -> db.close(Duration.ofMillis(250)),
                    "close must give up rather than park for ever");

            assertSame(held.thread, busy.owner(), "the exception hands over the holding thread");
            assertTrue(busy.getMessage().contains("leaky-worker"),
                    "the message names the thread: " + busy.getMessage());
            assertTrue(busy.getMessage().contains("write transaction"),
                    "and says what kind of block it is: " + busy.getMessage());
        }
        // Releasing the block leaves the store closable: the timeout wedged nothing.
        db.close(Duration.ofSeconds(5));
    }

    @Test
    void anOpenBlockIsVisibleToEveryThreadNotJustItsOwn() throws Exception {
        Path dir = TestStores.newDir("visible-block");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            assertFalse(db.hasOpenWriteBlock(), "nothing is open yet");
            assertNull(db.writeBlockOwner());
            assertEquals("nothing holds the lock", db.describeLockState());

            try (HeldBlock held = new HeldBlock(db, "holder")) {
                assertTrue(db.hasOpenWriteBlock(),
                        "the question isWriteActive() cannot answer for another thread");
                assertFalse(db.isWriteActive(), "and this thread is genuinely not writing");
                assertSame(held.thread, db.writeBlockOwner());
                assertTrue(db.describeLockState().contains("holder"), db.describeLockState());
            }

            assertFalse(db.hasOpenWriteBlock(), "and it is gone once the block ends");
            assertNull(db.writeBlockOwner());
        }
    }

    @Test
    void originTracingNamesTheLineThatOpenedTheBlock() throws Exception {
        Path dir = TestStores.newDir("origin-trace");
        ZeroZDb.traceWriteBlockOrigins(true);
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            try (HeldBlock held = new HeldBlock(db, "traced")) {
                String state = db.describeLockState();
                assertTrue(state.contains(HeldBlock.class.getName()),
                        "the captured stack must reach the code that called beginWrite(): " + state);
            }
        } finally {
            ZeroZDb.traceWriteBlockOrigins(false);
        }
    }

    @Test
    void withoutTracingTheMessageSaysHowToGetTheStack() throws Exception {
        Path dir = TestStores.newDir("origin-hint");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            try (HeldBlock held = new HeldBlock(db, "untraced")) {
                assertTrue(db.describeLockState().contains(WriteHold.TRACE_PROPERTY),
                        db.describeLockState());
            }
        }
    }

    @Test
    void closingFromInsideAWriteBlockIsRefused() {
        Path dir = TestStores.newDir("close-inside");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            // The lock is reentrant, so this would otherwise succeed and shut the storage manager
            // down underneath the block that is about to commit into it.
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> db.write(ctx -> db.close()));
            assertTrue(refused.getMessage().contains("inside a write block"), refused.getMessage());

            db.write(ctx -> {
                TestRoot root = db.root();
                ctx.edit(root.entries);
                root.entries.put("still", "usable");
            });
            assertEquals("usable", db.<TestRoot>root().entries.get("still"));
        }
    }

    @Test
    void closeStillWorksWhileReadsAreRunning() throws Exception {
        Path dir = TestStores.newDir("close-readers");
        ZeroZDb db = ZeroZDb.open(new TestRoot(), dir);
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread reader = new Thread(() -> db.read(() -> {
            inRead.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), "slow-reader");
        reader.start();
        assertTrue(inRead.await(5, TimeUnit.SECONDS));

        StoreBusyException busy = assertThrows(StoreBusyException.class,
                () -> db.close(Duration.ofMillis(250)));
        assertTrue(busy.getMessage().contains("read block"),
                "a held read block is reported too: " + busy.getMessage());

        release.countDown();
        reader.join(5000);
        db.close(Duration.ofSeconds(5));
    }

    @Test
    void aTransactionDroppedWithoutEndingIsReportedWhenItIsCollected() throws Exception {
        BlockingQueue<String> reports = new LinkedBlockingQueue<>();
        Consumer<String> previous = WriteBlockLeakWatch.reporter;
        WriteBlockLeakWatch.reporter = reports::add;
        try {
            dropWithoutEnding();
            String report = awaitReport(reports, Duration.ofSeconds(10));
            assertNotNull(report, "a dropped transaction must not vanish silently");
            assertTrue(report.contains("store-under-test"), report);
            assertTrue(report.contains("garbage-collected"), report);
        } finally {
            WriteBlockLeakWatch.reporter = previous;
        }
    }

    @Test
    void aTransactionThatEndedProperlyIsNeverReported() throws Exception {
        BlockingQueue<String> reports = new LinkedBlockingQueue<>();
        Consumer<String> previous = WriteBlockLeakWatch.reporter;
        WriteBlockLeakWatch.reporter = reports::add;
        try {
            dropAfterEnding();
            assertNull(awaitReport(reports, Duration.ofSeconds(2)),
                    "a committed transaction is not a leak");
        } finally {
            WriteBlockLeakWatch.reporter = previous;
        }
    }

    /**
     * A cross-store write refused because a plain block is already open must leave the lock as it
     * found it — {@code CrossStoreWrite} never registers a store it failed to begin, so nothing
     * else would ever release it.
     */
    @Test
    void aRefusedCrossStoreWriteLeavesTheStoreUsable() {
        Path dir = TestStores.newDir("cross-refused");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            assertThrows(IllegalStateException.class,
                    () -> db.write(ctx -> CrossStoreWrite.run(cross -> {
                    }, db)));

            assertFalse(db.hasOpenWriteBlock(), "the refusal released what it took");
            db.write(ctx -> {
                TestRoot root = db.root();
                ctx.edit(root.entries);
                root.entries.put("after", "ok");
            });
            assertEquals("ok", db.<TestRoot>root().entries.get("after"));
        }
    }

    /** The subject is unreachable the moment this returns, which is the leak being staged. */
    private static void dropWithoutEnding() {
        WriteBlockLeakWatch.watch(new Object(), "store-under-test",
                new WriteHold(WriteHold.TRANSACTION));
    }

    private static void dropAfterEnding() {
        WriteBlockLeakWatch.watch(new Object(), "store-under-test",
                new WriteHold(WriteHold.TRANSACTION)).ended();
    }

    private static String awaitReport(BlockingQueue<String> reports, Duration limit)
            throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            System.gc();
            String report = reports.poll(100, TimeUnit.MILLISECONDS);
            if (report != null) {
                return report;
            }
        }
        return null;
    }

    /** A write transaction opened on another thread and held until this is closed. */
    private static final class HeldBlock implements AutoCloseable {

        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Thread thread;

        HeldBlock(ZeroZDb db, String threadName) throws InterruptedException {
            thread = new Thread(() -> {
                try (WriteTransaction tx = db.beginWrite()) {
                    opened.countDown();
                    release.await();
                    tx.rollback();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, threadName);
            thread.start();
            assertTrue(opened.await(5, TimeUnit.SECONDS), "the write block never opened");
        }

        @Override
        public void close() throws InterruptedException {
            release.countDown();
            thread.join(5000);
        }
    }
}
