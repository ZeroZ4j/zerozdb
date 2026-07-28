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
package com.zeroz4j.db.example;

import org.junit.jupiter.api.Test;
import com.zeroz4j.db.UniqueConstraintException;
import com.zeroz4j.db.ZeroZDb;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end walk of the example client: a realistic app session including reopen — this test
 * doubles as living documentation of the intended consumer patterns.
 */
class ExampleClientTest {

    private static Path dir = Path.of("target", "test-stores", "example-" + System.nanoTime());

    @Test
    void fullClientSession() {
        long laptopId;

        try (ZeroZDb db = ZeroZDb.open(new ShopRoot(), dir)) {
            ProductService shop = new ProductService(db);

            laptopId = shop.addProduct("SKU-1", "Laptop stand", "office", 5125);
            long chairId = shop.addProduct("SKU-2", "Desk chair", "office", 24900);
            shop.addProduct("SKU-3", "Espresso cups", "kitchen", 1890);

            assertEquals(3, shop.productCount());
            assertEquals(4, shop.nextId(), "three sequential ids allocated");
            assertEquals(2, shop.inCategory("office").size());
            assertEquals("Laptop stand", shop.findBySku("SKU-1").orElseThrow().name);

            // Unique constraint: the whole block aborts — including the id counter bump.
            long nextIdBefore = shop.nextId();
            assertThrows(UniqueConstraintException.class,
                    () -> shop.addProduct("SKU-1", "Impostor", "office", 1));
            assertEquals(3, shop.productCount(), "impostor not added");
            assertEquals(nextIdBefore, shop.nextId(), "id counter rolled back with the block");

            // Key change moves the index entry.
            shop.recategorize(chairId, "furniture");
            assertEquals(1, shop.inCategory("office").size());
            assertEquals(1, shop.inCategory("furniture").size());

            shop.reprice(laptopId, 4999);
            assertTrue(shop.remove(chairId));
            assertFalse(shop.remove(chairId), "second remove is a no-op");
        }

        // Reopen: state is durable, a fresh service rebuilds its indexes from the store.
        try (ZeroZDb db = ZeroZDb.open(new ShopRoot(), dir)) {
            ProductService shop = new ProductService(db);
            assertEquals(2, shop.productCount());
            assertEquals(4, shop.nextId(), "counter survives reopen — the zeroz4j two-commit bug shape, fixed");
            assertEquals(4999, shop.findBySku("SKU-1").orElseThrow().priceCents);
            assertTrue(shop.inCategory("furniture").isEmpty(), "removed product gone from index");
            assertEquals(1, shop.inCategory("kitchen").size());
        }
    }
}
