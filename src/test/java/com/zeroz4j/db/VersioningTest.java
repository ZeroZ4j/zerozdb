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

class VersioningTest {

    /**
     * The classic think-time conflict: Alice opens a form (baseline), Bob saves, Alice saves.
     * Alice's checked store must fail, roll back her block entirely, and leave Bob's data.
     */
    @Test
    void staleEditIsDetectedAndRolledBack() {
        Path dir = TestStores.newDir("ver-stale");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.entries.put("phone", "111");
                ctx.store(root.entries);
            });

            long aliceBaseline = db.baseline(root.entries);   // Alice opens the form

            db.write(ctx -> {                                  // Bob saves meanwhile
                ctx.edit(root.entries);
                root.entries.put("phone", "222-bob");
            });

            assertThrows(StaleObjectException.class, () -> db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("phone", "333-alice");
                ctx.storeChecked(root.entries, aliceBaseline);
            }));
            db.read(() -> assertEquals("222-bob", root.entries.get("phone"),
                    "Bob's save survives; Alice's stale save rolled back"));
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            assertEquals("222-bob", root.entries.get("phone"), "disk agrees");
        }
    }

    @Test
    void cleanCheckedEditSucceedsAndBumpsVersion() {
        Path dir = TestStores.newDir("ver-clean");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            long baseline = db.baseline(root.entries);
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "v1");
                ctx.storeChecked(root.entries, baseline);
            });
            long after = db.baseline(root.entries);
            assertEquals(baseline + 1, after, "commit bumps the tracked version");

            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "v2");
                ctx.storeChecked(root.entries, after);
            });
            assertEquals("v2", db.<TestRoot>root().entries.get("k"));
        }
    }

    @Test
    void uncheckedStoresRemainLastWriteWins() {
        Path dir = TestStores.newDir("ver-lww");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "first");
            });
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "second");
            });
            assertEquals("second", root.entries.get("k"));
        }
    }

    @Test
    void untrackedObjectsAreNotVersionedUntilFirstBaseline() {
        Path dir = TestStores.newDir("ver-untracked");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("k", "v");
            });
            assertEquals(0, db.baseline(root.entries),
                    "commits before first baseline cost nothing and count nothing");
        }
    }
}
