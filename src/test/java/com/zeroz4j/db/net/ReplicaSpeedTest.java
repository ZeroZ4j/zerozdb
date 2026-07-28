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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The point of replicas: a client's reads stop being round trips. Compares remote queries
 * against local replica reads on the same client node.
 */
class ReplicaSpeedTest {

    @Test
    void localReplicaReadsAreOrdersOfMagnitudeFasterThanRemoteQueries() {
        Path storeDir = Path.of("target", "test-stores", "replica-speed-" + System.nanoTime());
        int reads = 20_000;

        try (ZeroZDbNode owner = ZeroZDbNode.open(storeDir, TestRoot::new);
             ZeroZDbNode client = ZeroZDbNode.builder(storeDir, TestRoot::new)
                     .allowPromotion(false).build()) {

            for (int i = 0; i < 200; i++) {
                owner.execute(new Commands.Put("k" + i, "v" + i));
            }

            try (ZeroZDbNode.LocalReads<TestRoot> local = client.localReads()) {
                // let the replica catch up
                long deadline = System.currentTimeMillis() + 20_000;
                while (local.read(root -> root.entries.size()) < 200
                        && System.currentTimeMillis() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(200, (int) local.read(root -> root.entries.size()));

                long remoteStart = System.nanoTime();
                for (int i = 0; i < reads / 20; i++) {          // fewer: each is a round trip
                    client.query(new Commands.Size());
                }
                long remoteNanos = (System.nanoTime() - remoteStart) / (reads / 20);

                long localStart = System.nanoTime();
                int sink = 0;
                for (int i = 0; i < reads; i++) {
                    sink += local.read(root -> root.entries.size());
                }
                long localNanos = (System.nanoTime() - localStart) / reads;
                assertTrue(sink > 0);

                System.out.printf("replica read %d ns vs remote query %d ns (%.0fx)%n",
                        localNanos, remoteNanos, remoteNanos / (double) Math.max(1, localNanos));
                assertTrue(localNanos * 10 < remoteNanos,
                        "local replica reads should be at least 10x faster: local " + localNanos
                                + " ns vs remote " + remoteNanos + " ns");
            }
        }
    }
}
