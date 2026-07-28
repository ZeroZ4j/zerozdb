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
import com.zeroz4j.db.ZeroZDb;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplicaTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void replicaServesReadsLocallyAndCatchesUpAfterCommits() throws Exception {
        Path storeDir = dir("replica-basic");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient writer = ZeroZDbClient.connect("127.0.0.1", server.port(), "default");
             ReplicaView<TestRoot> replica = ReplicaView.connect(
                     "127.0.0.1", server.port(), "default", "main")) {

            writer.execute("main", new Commands.Put("k", "v1"));
            waitForValue(replica, "k", "v1");
            assertEquals("v1", replica.read(root -> root.entries.get("k")));

            long requestsBefore = server.requestsServed();
            for (int i = 0; i < 1000; i++) {
                assertEquals("v1", replica.read(root -> root.entries.get("k")));
            }
            long refreshTraffic = server.requestsServed() - requestsBefore;
            assertTrue(refreshTraffic <= 4,
                    "1000 local reads must not hit the wire; server saw " + refreshTraffic
                            + " requests (background refresh only)");

            writer.execute("main", new Commands.Put("k", "v2"));
            waitForValue(replica, "k", "v2");
            assertEquals("v2", replica.read(root -> root.entries.get("k")));
            assertTrue(replica.refreshCount() >= 2, "replica rebuilt after the commit");
        }
    }

    @Test
    void replicaSnapshotsAreInternallyConsistent() throws Exception {
        Path storeDir = dir("replica-consistent");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient writer = ZeroZDbClient.connect("127.0.0.1", server.port(), "default");
             ReplicaView<TestRoot> replica = ReplicaView.connect(
                     "127.0.0.1", server.port(), "default", "main")) {

            Thread reader = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 3000; i++) {
                    // a and b are always written together, so a replica must never see them differ
                    replica.read(root -> {
                        if (root.a != root.b) {
                            throw new AssertionError("torn replica snapshot: a=" + root.a
                                    + " b=" + root.b);
                        }
                        return null;
                    });
                }
            });

            for (int i = 1; i <= 60; i++) {
                writer.execute("main", new Commands.SetPair(i));
            }
            reader.join(60_000);
            assertTrue(!reader.isAlive());
        }
    }

    @Test
    void replicaIsIndependentOfTheServerGraph() throws Exception {
        Path storeDir = dir("replica-independent");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient writer = ZeroZDbClient.connect("127.0.0.1", server.port(), "default");
             ReplicaView<TestRoot> replica = ReplicaView.connect(
                     "127.0.0.1", server.port(), "default", "main")) {

            writer.execute("main", new Commands.Put("k", "server-value"));
            waitForValue(replica, "k", "server-value");

            // Mutating the local copy must not affect the owner (it is a copy, not a reference).
            replica.root().entries.put("k", "local-scribble");
            assertEquals("server-value", writer.query("main", new Commands.Get("k")));
        }
    }

    private static void waitForValue(ReplicaView<TestRoot> replica, String key, String expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(replica.read(root -> root.entries.get(key)))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replica never caught up to " + key + "=" + expected);
    }
}
