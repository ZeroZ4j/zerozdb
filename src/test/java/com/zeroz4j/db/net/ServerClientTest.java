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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerClientTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void commandsAndQueriesRoundTripOverTheWire() {
        Path storeDir = dir("net-basic");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).schemaId("v1").start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "v1")) {

            assertEquals(java.util.Set.of("main"), client.stores());

            assertNull(client.execute("main", new Commands.Put("k", "v1")));
            assertEquals("v1", client.query("main", new Commands.Get("k")));
            assertEquals("v1", client.execute("main", new Commands.Put("k", "v2")),
                    "command result (previous value) returns over the wire");
            assertEquals("v2", client.query("main", new Commands.Get("k")));
            assertEquals(1, client.query("main", new Commands.Size()));

            // The server-side write actually reached the live graph.
            assertEquals("v2", db.<TestRoot>root().entries.get("k"));
        }
    }

    @Test
    void writesArePersistedAndSurviveServerRestart() {
        Path storeDir = dir("net-durable");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "default")) {
            client.execute("main", new Commands.Put("persisted", "yes"));
        }
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "default")) {
            assertEquals("yes", client.query("main", new Commands.Get("persisted")));
        }
    }

    @Test
    void failedCommandRollsBackOnServerAndReportsToClient() {
        Path storeDir = dir("net-failure");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "default")) {

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.execute("main", new Commands.Boom()));
            assertTrue(failure.getMessage().contains("on purpose"));
            assertEquals(0, client.query("main", new Commands.Size()),
                    "failed command rolled back server-side");
            assertTrue(db.<TestRoot>root().entries.isEmpty());
        }
    }

    @Test
    void schemaMismatchIsRefusedAtConnect() {
        Path storeDir = dir("net-schema");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).schemaId("v2").start()) {
            assertThrows(SchemaMismatchException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(), "v1"));
            assertEquals(1, server.connectionsRejected());
        }
    }

    @Test
    void unknownStoreIsRejected() {
        Path storeDir = dir("net-unknown");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start();
             ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(), "default")) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.query("nope", new Commands.Size()));
        }
    }

    @Test
    void manyConcurrentClientsSerializeCorrectly() throws Exception {
        Path storeDir = dir("net-concurrent");
        int clients = 16;
        int perClient = 25;
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder().store("main", db).start()) {

            List<ZeroZDbClient> pool = ZeroZDbClient.connectMany(
                    "127.0.0.1", server.port(), "default", clients);
            try {
                List<Thread> threads = new java.util.ArrayList<>();
                for (int c = 0; c < clients; c++) {
                    int clientIndex = c;
                    ZeroZDbClient client = pool.get(c);
                    threads.add(Thread.ofVirtual().start(() -> {
                        for (int i = 0; i < perClient; i++) {
                            client.execute("main", new Commands.Put(
                                    "c" + clientIndex + "-" + i, "v"));
                        }
                    }));
                }
                for (Thread t : threads) {
                    t.join(60_000);
                }
                assertEquals(clients * perClient, client(pool).query("main", new Commands.Size()),
                        "every concurrent remote write landed exactly once");
            } finally {
                pool.forEach(ZeroZDbClient::close);
            }
        }
    }

    private static ZeroZDbClient client(List<ZeroZDbClient> pool) {
        return pool.get(0);
    }
}
