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
package com.zeroz4j.db.net;

import org.junit.jupiter.api.Test;
import com.zeroz4j.db.TestRoot;
import com.zeroz4j.db.lease.LeaseFileArbiter;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container/shared-volume path: ownership decided by lease rather than OS file lock, which
 * is what makes cross-host deployment possible.
 */
class LeaseFailoverTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    private static ZeroZDbNode node(Path storeDir, String ownerId) {
        return ZeroZDbNode.builder(storeDir, TestRoot::new)
                .arbiter(new LeaseFileArbiter(Duration.ofMillis(200), Duration.ofMillis(600),
                        Duration.ofMillis(200)))
                .ownerId(ownerId)
                .build();
    }

    @Test
    void leaseDecidesOwnershipAndTheLoserBecomesAClient() {
        Path storeDir = dir("lease-node-basic");
        try (ZeroZDbNode first = node(storeDir, "node-1");
             ZeroZDbNode second = node(storeDir, "node-2")) {

            assertTrue(first.isServing(), "first node holds the lease and serves");
            assertFalse(second.isOwner(), "second node joined as a client");

            second.execute(new Commands.Put("k", "written-by-client"));
            assertEquals("written-by-client", first.query(new Commands.Get("k")));
        }
    }

    @Test
    void ownerHandsOverCleanlyAndDataSurvives() {
        Path storeDir = dir("lease-node-handover");
        ZeroZDbNode owner = node(storeDir, "node-1");
        try (ZeroZDbNode survivor = node(storeDir, "node-2")) {
            survivor.execute(new Commands.Put("before", "handover"));
            assertFalse(survivor.isOwner());

            owner.close();                       // releases the lease immediately

            assertEquals("handover", survivor.query(new Commands.Get("before")),
                    "the survivor recovers transparently");
            assertTrue(survivor.isOwner(), "survivor took the lease and now owns the store");

            survivor.execute(new Commands.Put("after", "promotion"));
            assertEquals(2, (int) survivor.query(new Commands.Size()));
        }
    }

    @Test
    void displacedOwnerStopsServingInsteadOfWritingAlongsideTheNewOwner() throws Exception {
        Path storeDir = dir("lease-node-stepdown");
        try (ZeroZDbNode owner = node(storeDir, "node-1")) {
            assertTrue(owner.isServing());
            owner.execute(new Commands.Put("k", "v"));

            // Simulate a takeover by a node that observed the lease expire (e.g. after this JVM
            // was frozen). The incumbent must notice and stand down rather than keep writing.
            new LeaseFileArbiter(Duration.ofMillis(200), Duration.ofSeconds(30),
                    Duration.ofMillis(200)).forceAcquire(storeDir, "node-2");

            long deadline = System.currentTimeMillis() + 15_000;
            while (owner.isServing() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(owner.isServing(),
                    "a displaced owner must stop serving its store");
        }
    }
}
