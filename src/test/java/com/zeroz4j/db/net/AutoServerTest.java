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

class AutoServerTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void firstNodeOwnsAndSecondBecomesClientAutomatically() {
        Path storeDir = dir("auto-basic");
        try (ZeroZDbNode first = ZeroZDbNode.open(storeDir, TestRoot::new);
             ZeroZDbNode second = ZeroZDbNode.open(storeDir, TestRoot::new)) {

            assertTrue(first.isOwner(), "first node owns the store");
            assertFalse(second.isOwner(), "second node joined as a client");
            assertNotNull(first.localDb());
            assertNull(second.localDb(), "clients have no local engine");
            assertEquals(first.port(), second.port(), "client found the owner's endpoint");

            // Same API, either role.
            second.execute(new Commands.Put("from-client", "v"));
            first.execute(new Commands.Put("from-owner", "v"));

            assertEquals("v", first.query(new Commands.Get("from-client")),
                    "owner sees the client's write");
            assertEquals("v", second.query(new Commands.Get("from-owner")),
                    "client sees the owner's write");
            assertEquals(2, (int) second.query(new Commands.Size()));
        }
    }

    @Test
    void clientPromotesItselfWhenTheOwnerGoesAway() {
        Path storeDir = dir("auto-promote");
        ZeroZDbNode owner = ZeroZDbNode.open(storeDir, TestRoot::new);
        try (ZeroZDbNode survivor = ZeroZDbNode.open(storeDir, TestRoot::new)) {
            assertTrue(owner.isOwner());
            assertFalse(survivor.isOwner());

            survivor.execute(new Commands.Put("before", "handover"));
            owner.close();

            // The next call transparently recovers: no owner exists, so this node takes over.
            assertEquals("handover", survivor.query(new Commands.Get("before")),
                    "data survives the handover");
            assertTrue(survivor.isOwner(), "survivor promoted itself to owner");

            survivor.execute(new Commands.Put("after", "promotion"));
            assertEquals(2, (int) survivor.query(new Commands.Size()));
        }
    }

    @Test
    void newNodeAfterHandoverJoinsThePromotedOwner() {
        Path storeDir = dir("auto-rejoin");
        ZeroZDbNode first = ZeroZDbNode.open(storeDir, TestRoot::new);
        try (ZeroZDbNode second = ZeroZDbNode.open(storeDir, TestRoot::new)) {
            second.execute(new Commands.Put("k", "v"));
            first.close();
            second.query(new Commands.Size());          // triggers promotion
            assertTrue(second.isOwner());

            try (ZeroZDbNode third = ZeroZDbNode.open(storeDir, TestRoot::new)) {
                assertFalse(third.isOwner(), "third node joins the new owner");
                assertEquals(second.port(), third.port());
                assertEquals("v", third.query(new Commands.Get("k")));
            }
        }
    }

    @Test
    void clientOnlyNodeNeverTakesOwnership() {
        Path storeDir = dir("auto-clientonly");
        try (ZeroZDbNode owner = ZeroZDbNode.open(storeDir, TestRoot::new);
             ZeroZDbNode clientOnly = ZeroZDbNode.builder(storeDir, TestRoot::new)
                     .allowPromotion(false).build()) {
            assertTrue(owner.isOwner());
            assertFalse(clientOnly.isOwner());
            clientOnly.execute(new Commands.Put("k", "v"));
            assertEquals("v", owner.query(new Commands.Get("k")));
        }
    }

    @Test
    void clientOnlyNodeCannotStartWithoutAnOwner() {
        Path storeDir = dir("auto-noowner");
        assertThrows(IllegalStateException.class, () -> ZeroZDbNode.builder(storeDir, TestRoot::new)
                .allowPromotion(false).build());
    }

    @Test
    void ownerPublishesAndRemovesItsEndpoint() {
        Path storeDir = dir("auto-endpoint");
        try (ZeroZDbNode owner = ZeroZDbNode.open(storeDir, TestRoot::new)) {
            Endpoint endpoint = Endpoint.read(storeDir);
            assertNotNull(endpoint);
            assertEquals(owner.port(), endpoint.port());
            assertEquals(ProcessHandle.current().pid(), endpoint.pid());
        }
        assertNull(Endpoint.read(storeDir), "endpoint withdrawn on clean shutdown");
    }
}
