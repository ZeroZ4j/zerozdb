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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteBlockTest {

    @Test
    void commitPersistsAllTouchedObjectsAcrossReopen() {
        Path dir = TestStores.newDir("atomic");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.entries.put("k1", "v1");
                root.entries.put("k2", "v2");
                ctx.store(root.entries);
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertEquals("v1", root.entries.get("k1"));
            assertEquals("v2", root.entries.get("k2"));
        }
    }

    @Test
    void exceptionInBlockPersistsNothing() {
        Path dir = TestStores.newDir("abort");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                root.entries.put("doomed", "x");
                ctx.store(root.entries);
                throw new RuntimeException("boom");
            }));
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertTrue(root.entries.isEmpty(), "aborted block must not reach disk");
        }
    }

    @Test
    void nestedWriteJoinsOuterBlockAndCommitsOnce() {
        Path dir = TestStores.newDir("nested");
        AtomicLong commits = new AtomicLong();
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            db.addCommitListener((seq, count) -> commits.incrementAndGet());
            TestRoot root = db.root();
            db.write(outer -> {
                root.entries.put("outer", "1");
                outer.store(root.entries);
                db.write(inner -> {
                    root.entries.put("inner", "2");
                    inner.store(root.entries);
                });
            });
            assertEquals(1, commits.get(), "nested block must join the outer commit");
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertEquals("1", root.entries.get("outer"));
            assertEquals("2", root.entries.get("inner"));
        }
    }

    @Test
    void escapedContextThrows() {
        Path dir = TestStores.newDir("escape");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            AtomicReference<WriteContext> escaped = new AtomicReference<>();
            db.write(escaped::set);
            assertThrows(IllegalStateException.class, () -> escaped.get().store(new Object()));
        }
    }

    @Test
    void writeReturnsBlockValue() {
        Path dir = TestStores.newDir("retval");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            String value = db.writeResult(ctx -> {
                root.entries.put("k", "computed");
                ctx.store(root.entries);
                return root.entries.get("k");
            });
            assertEquals("computed", value);
        }
    }
}
