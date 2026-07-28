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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shape a multi-tenant application needs: a few shared stores served to several JVMs, plus many
 * per-tenant stores that stay private to one JVM — same API for both.
 */
class MixedTopologyTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void embeddedNodeOwnsLocallyAndServesNobody() {
        Path storeDir = dir("mode-embedded");
        try (ZeroZDbNode tenant = ZeroZDbNode.embedded(storeDir, TestRoot::new)) {
            assertEquals(ZeroZDbNode.Mode.EMBEDDED, tenant.mode());
            assertTrue(tenant.isOwner(), "embedded node owns its data");
            assertFalse(tenant.isServing(), "embedded node opens no socket");
            assertNotNull(tenant.localDb(), "the engine is available for lambda write-blocks");
            assertThrows(IllegalStateException.class, tenant::port);
            assertNull(Endpoint.read(storeDir), "no endpoint is published");

            // Identical API to a served store.
            tenant.execute(new Commands.Put("k", "v"));
            assertEquals("v", tenant.query(new Commands.Get("k")));
            try (ZeroZDbNode.LocalReads<TestRoot> local = tenant.localReads()) {
                assertEquals("v", local.read(root -> root.entries.get("k")));
            }
        }
    }

    @Test
    void embeddedStoresStayPrivateWhileSharedStoresAreServed() {
        Path sharedDir = dir("mode-shared");
        Path tenantDir = dir("mode-tenant");

        try (ZeroZDbNode sharedOwner = ZeroZDbNode.open(sharedDir, TestRoot::new);
             ZeroZDbNode sharedClient = ZeroZDbNode.builder(sharedDir, TestRoot::new)
                     .mode(ZeroZDbNode.Mode.CLIENT_ONLY).build();
             ZeroZDbNode tenant = ZeroZDbNode.embedded(tenantDir, TestRoot::new)) {

            assertTrue(sharedOwner.isServing());
            assertFalse(sharedClient.isOwner());
            assertFalse(tenant.isServing());

            sharedClient.execute(new Commands.Put("shared", "from-client"));
            tenant.execute(new Commands.Put("tenant", "local-only"));

            assertEquals("from-client", sharedOwner.query(new Commands.Get("shared")));
            assertEquals(1, (int) tenant.query(new Commands.Size()));
            assertNull(sharedOwner.query(new Commands.Get("tenant")),
                    "tenant data never reaches the shared store");
        }
    }

    @Test
    void embeddedNodeSupportsFullTransactionSemantics() {
        Path storeDir = dir("mode-embedded-txn");
        try (ZeroZDbNode tenant = ZeroZDbNode.embedded(storeDir, TestRoot::new)) {
            // Lambda write-blocks remain available on any node that owns its data.
            TestRoot root = tenant.localDb().root();
            assertThrows(RuntimeException.class, () -> tenant.localDb().write(ctx -> {
                ctx.edit(root.entries);
                root.entries.put("doomed", "x");
                throw new RuntimeException("boom");
            }));
            assertTrue(root.entries.isEmpty(), "rollback works exactly as embedded");

            assertThrows(IllegalStateException.class, () -> tenant.execute(new Commands.Boom()));
            assertEquals(0, (int) tenant.query(new Commands.Size()));
        }
    }

    @Test
    void embeddedNodeRefusesASecondOpener() {
        Path storeDir = dir("mode-embedded-excl");
        try (ZeroZDbNode first = ZeroZDbNode.embedded(storeDir, TestRoot::new)) {
            assertThrows(RuntimeException.class,
                    () -> ZeroZDbNode.embedded(storeDir, TestRoot::new),
                    "an embedded store is private: a second opener must be refused, not served");
        }
    }
}
