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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting a write inside a read block cannot work — a read lock cannot be upgraded — so the
 * engine must say so rather than hang. These tests exist because the alternative failure mode is
 * a process that stops responding with no message, which is the worst thing a database can do to
 * an operator.
 */
class LockUpgradeTest {

    @Test
    void startingAWriteInsideAReadBlockFailsLoudly() {
        Path dir = TestStores.newDir("upgrade-block");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> db.read(() -> db.write(ctx -> {
                        ctx.edit(root.entries);
                        root.entries.put("k", "v");
                    })));
            assertTrue(failure.getMessage().contains("deadlock"), failure.getMessage());
        }
    }

    @Test
    void beginningATransactionInsideAReadBlockFailsLoudly() {
        Path dir = TestStores.newDir("upgrade-tx");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            assertThrows(IllegalStateException.class, () -> db.read(db::beginWrite));
        }
    }

    /** The opposite nesting is legitimate: a write may read, because downgrading is allowed. */
    @Test
    void readingInsideAWriteIsFine() {
        Path dir = TestStores.newDir("downgrade");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "v");
                String seen = db.read(() -> root.entries.get("k"));
                assertEquals("v", seen, "a writer reads its own uncommitted work");
            });
            assertEquals("v", db.read(() -> root.entries.get("k")));
        }
    }

    /** And the store is still usable after the failure — nothing was left locked. */
    @Test
    void theStoreSurvivesARefusedUpgrade() {
        Path dir = TestStores.newDir("upgrade-survive");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertThrows(IllegalStateException.class, () -> db.read(db::beginWrite));
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("after", "ok");
            });
            assertEquals("ok", db.read(() -> root.entries.get("after")));
        }
    }
}
