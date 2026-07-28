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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A node pointed at a server by address rather than by a file beside the store — the only form
 * that works when the server is on another host, and the one a framework configures from
 * properties.
 */
class RemoteNodeTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void aNodeCanBePointedAtAServerByAddress() {
        Path serverDir = dir("remote-server");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), serverDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).schemaId("v1").start();
             // Deliberately a directory the server knows nothing about: no endpoint file exists
             // there, so this can only work through the configured address.
             ZeroZDbNode node = ZeroZDbNode.builder(dir("remote-client"), TestRoot::new)
                     .schemaId("v1")
                     .remote("127.0.0.1", server.port())
                     .build()) {

            assertFalse(node.isOwner(), "a remote node must never take ownership");
            node.execute(new Commands.Put("k", "v"));
            assertEquals("v", node.query(new Commands.Get("k")));
            assertEquals("v", db.read(() -> ((TestRoot) db.root()).entries.get("k")));
        }
    }

    @Test
    void aRemoteNodePresentsItsSecret() {
        Path serverDir = dir("remote-auth");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), serverDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).schemaId("v1").secret("s3cret").start()) {

            try (ZeroZDbNode node = ZeroZDbNode.builder(dir("remote-auth-ok"), TestRoot::new)
                    .schemaId("v1").secret("s3cret")
                    .remote("127.0.0.1", server.port()).build()) {
                node.execute(new Commands.Put("k", "v"));
                assertEquals("v", node.query(new Commands.Get("k")));
            }

            assertThrows(IllegalStateException.class, () ->
                    ZeroZDbNode.builder(dir("remote-auth-bad"), TestRoot::new)
                            .schemaId("v1").secret("wrong")
                            .remote("127.0.0.1", server.port()).build(),
                    "a wrong secret must not yield a usable node");
        }
    }

    @Test
    void remoteNodesReadLocallyThroughAReplica() throws Exception {
        Path serverDir = dir("remote-replica");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), serverDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).schemaId("v1").start();
             ZeroZDbNode node = ZeroZDbNode.builder(dir("remote-replica-client"), TestRoot::new)
                     .schemaId("v1").remote("127.0.0.1", server.port()).build()) {

            node.execute(new Commands.Put("k", "v"));
            try (ZeroZDbNode.LocalReads<TestRoot> local = node.localReads()) {
                long deadline = System.currentTimeMillis() + 20_000;
                while (local.read(root -> root.entries.get("k")) == null
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals("v", local.read(root -> root.entries.get("k")));
            }
        }
    }
}
