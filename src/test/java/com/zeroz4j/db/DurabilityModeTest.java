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

class DurabilityModeTest {

    @Test
    void syncModeRoundTripsAcrossReopen() {
        Path dir = TestStores.newDir("dura-sync");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir, Durability.SYNC)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.entries.put("mode", "sync");
                ctx.store(root.entries);
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir, Durability.SYNC)) {
            TestRoot root = db.root();
            assertEquals("sync", root.entries.get("mode"));
        }
    }

    @Test
    void osBufferedModeRoundTripsAcrossReopen() {
        Path dir = TestStores.newDir("dura-buffered");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir, Durability.OS_BUFFERED)) {
            TestRoot root = db.root();
            db.write(ctx -> {
                root.entries.put("mode", "buffered");
                ctx.store(root.entries);
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir, Durability.OS_BUFFERED)) {
            TestRoot root = db.root();
            assertEquals("buffered", root.entries.get("mode"));
        }
    }
}
