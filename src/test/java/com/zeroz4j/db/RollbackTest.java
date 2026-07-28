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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RollbackTest {

    @Test
    void failedBlockRollsBackFieldMutationsInMemory() {
        Path dir = TestStores.newDir("rb-fields");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.a = 42;
                ctx.store(root);
            });
            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.edit(root);
                root.a = 99;
                root.b = 99;
                throw new RuntimeException("boom");
            }));
            db.read(() -> {
                assertEquals(42, root.a, "field mutation must be rolled back in memory");
                assertEquals(0, root.b);
            });
        }
    }

    @Test
    void failedBlockRollsBackMapContentsInMemory() {
        Path dir = TestStores.newDir("rb-map");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.entries.put("keep", "me");
                ctx.store(root.entries);
            });
            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("doomed", "x");
                root.entries.remove("keep");
                throw new RuntimeException("boom");
            }));
            db.read(() -> {
                assertEquals("me", root.entries.get("keep"), "removed entry must be restored");
                assertTrue(!root.entries.containsKey("doomed"), "added entry must be gone");
            });
        }
    }

    @Test
    void onRollbackActionsRunNewestFirst() {
        Path dir = TestStores.newDir("rb-undo");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            List<String> order = new ArrayList<>();
            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.onRollback(() -> order.add("first-registered"));
                ctx.onRollback(() -> order.add("second-registered"));
                throw new RuntimeException("boom");
            }));
            assertEquals(List.of("second-registered", "first-registered"), order);
        }
    }

    @Test
    void successfulBlockKeepsMutations() {
        Path dir = TestStores.newDir("rb-success");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root);
                root.a = 7;
            });
            db.read(() -> assertEquals(7, root.a));
        }
    }
}
