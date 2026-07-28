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
package com.zeroz4j.db.console;

import org.junit.jupiter.api.Test;
import com.zeroz4j.db.ZeroZDb;
import com.zeroz4j.db.net.DbQuery;
import com.zeroz4j.db.schema.SchemaCompatibility;
import com.zeroz4j.db.schema.SchemaDescriptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleServerTest {

    public static class Shop {
        public java.util.Map<String, Item> items = new java.util.LinkedHashMap<>();
        public String owner = "Franz";
        public long nextId = 7;
    }

    public static class Item {
        public String sku;
        public String category;
        public long priceCents;

        public Item() {
        }

        public Item(String sku, String category, long priceCents) {
            this.sku = sku;
            this.category = category;
            this.priceCents = priceCents;
        }
    }

    public static class CountItems implements DbQuery<Integer> {
        @Override
        public Integer execute(Object root) {
            return ((Shop) root).items.size();
        }
    }

    public record FindBySku(String sku) implements DbQuery<String> {
        @Override
        public String execute(Object root) {
            Item item = ((Shop) root).items.get(sku);
            return item == null ? "not found" : item.sku + " / " + item.category;
        }
    }

    private static String body(ConsoleServer console, String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(console.url() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return response.body();
    }

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    private static ZeroZDb seededStore(Path storeDir) {
        ZeroZDb db = ZeroZDb.open(new Shop(), storeDir);
        Shop shop = db.root();
        db.write(ctx -> {
            ctx.edit(shop.items);
            shop.items.put("SKU-1", new Item("SKU-1", "office", 5125));
            shop.items.put("SKU-2", new Item("SKU-2", "kitchen", 1890));
        });
        return db;
    }

    @Test
    void overviewReportsStoresAndCommits() throws Exception {
        try (ZeroZDb db = seededStore(dir("console-overview"));
             ConsoleServer console = ConsoleServer.builder().store("shop", db).start()) {
            String json = body(console, "api/overview");
            assertTrue(json.contains("\"shop\""), json);
            assertTrue(json.contains("commitSequence"), json);
            assertTrue(json.contains("ConsoleServerTest$Shop"), json);
        }
    }

    @Test
    void dataBrowserWalksTheDomainGraphByFieldName() throws Exception {
        try (ZeroZDb db = seededStore(dir("console-browse"));
             ConsoleServer console = ConsoleServer.builder().store("shop", db).start()) {

            String root = body(console, "api/browse?store=shop");
            assertTrue(root.contains("\"owner\""), root);
            assertTrue(root.contains("Franz"), root);
            assertTrue(root.contains("\"items\""), root);

            String items = body(console, "api/browse?store=shop&path=items");
            assertTrue(items.contains("\"kind\":\"map\""), items);
            assertTrue(items.contains("SKU-1"), items);

            String item = body(console, "api/browse?store=shop&path=items/SKU-1");
            assertTrue(item.contains("\"category\""), item);
            assertTrue(item.contains("office"), item);
            assertTrue(item.contains("5125"), item);

            String missing = body(console, "api/browse?store=shop&path=items/NOPE");
            assertTrue(missing.contains("\"value\":null"), missing);
        }
    }

    @Test
    void collectionsArePaged() throws Exception {
        Path storeDir = dir("console-paging");
        try (ZeroZDb db = ZeroZDb.open(new Shop(), storeDir)) {
            Shop shop = db.root();
            db.write(ctx -> {
                ctx.edit(shop.items);
                for (int i = 0; i < 120; i++) {
                    shop.items.put("SKU-" + i, new Item("SKU-" + i, "bulk", i));
                }
            });
            try (ConsoleServer console = ConsoleServer.builder().store("shop", db).start()) {
                String page = body(console, "api/browse?store=shop&path=items&offset=10&limit=5");
                assertTrue(page.contains("\"size\":120"), page);
                assertTrue(page.contains("SKU-10"), page);
                assertTrue(!page.contains("\"SKU-16\""), "page must stop at the limit: " + page);
            }
        }
    }

    @Test
    void namedQueriesRunWithParameters() throws Exception {
        try (ZeroZDb db = seededStore(dir("console-queries"));
             ConsoleServer console = ConsoleServer.builder()
                     .store("shop", db)
                     .queries("shop", new QueryCatalog()
                             .register("count", "How many items", new CountItems())
                             .register("by-sku", "Find an item by SKU", List.of("sku"),
                                     params -> new FindBySku(params.get("sku"))))
                     .start()) {

            String catalog = body(console, "api/queries?store=shop");
            assertTrue(catalog.contains("by-sku"), catalog);
            assertTrue(catalog.contains("\"parameters\":[\"sku\"]"), catalog);

            String count = body(console, "api/run?store=shop&query=count");
            assertTrue(count.contains("\"result\":\"2\""), count);

            String found = body(console, "api/run?store=shop&query=by-sku&sku=SKU-2");
            assertTrue(found.contains("kitchen"), found);
        }
    }

    @Test
    void schemaViewDiffsAgainstTheCommittedBaseline() throws Exception {
        Path baseline = dir("console-schema").resolve("baseline.txt");
        Files.createDirectories(baseline.getParent());
        // Baseline knows Item without 'category' — i.e. the running model has added a field.
        SchemaDescriptor.parse("""
                com.zeroz4j.db.console.ConsoleServerTest$Item sku java.lang.String
                com.zeroz4j.db.console.ConsoleServerTest$Item priceCents long
                """).write(baseline);

        try (ZeroZDb db = seededStore(dir("console-schema-store"));
             ConsoleServer console = ConsoleServer.builder()
                     .store("shop", db)
                     .model(Item.class)
                     .schemaBaseline(baseline)
                     .start()) {

            String json = body(console, "api/schema");
            assertTrue(json.contains("\"rollbackCompatible\":true"), json);
            assertTrue(json.contains("SAFE"), json);
            assertTrue(json.contains("category"), json);
        }
    }

    @Test
    void schemaViewFlagsAnIncompatibleRunningModel() throws Exception {
        Path baseline = dir("console-schema-bad").resolve("baseline.txt");
        Files.createDirectories(baseline.getParent());
        // Baseline has a field the running model no longer declares.
        SchemaDescriptor.parse("""
                com.zeroz4j.db.console.ConsoleServerTest$Item sku java.lang.String
                com.zeroz4j.db.console.ConsoleServerTest$Item category java.lang.String
                com.zeroz4j.db.console.ConsoleServerTest$Item priceCents long
                com.zeroz4j.db.console.ConsoleServerTest$Item retiredField java.lang.String
                """).write(baseline);

        try (ZeroZDb db = seededStore(dir("console-schema-bad-store"));
             ConsoleServer console = ConsoleServer.builder()
                     .store("shop", db).model(Item.class).schemaBaseline(baseline).start()) {
            String json = body(console, "api/schema");
            assertTrue(json.contains("\"rollbackCompatible\":false"), json);
            assertTrue(json.contains("ROLLBACK_BREAKING"), json);
            assertTrue(json.contains("retiredField"), json);
        }
    }

    @Test
    void consoleRefusesNonLoopbackWithoutAPassword() {
        try (ZeroZDb db = seededStore(dir("console-bind"))) {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> ConsoleServer.builder().store("shop", db)
                            .bindAddress("0.0.0.0").start());
            assertTrue(refusal.getMessage().contains("without a password"), refusal.getMessage());
        }
    }

    @Test
    void passwordProtectedConsoleRejectsUnauthenticatedRequests() throws Exception {
        try (ZeroZDb db = seededStore(dir("console-auth"));
             ConsoleServer console = ConsoleServer.builder()
                     .store("shop", db).password("letmein").start()) {

            HttpResponse<String> denied = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(console.url() + "api/overview")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());

            String credentials = java.util.Base64.getEncoder()
                    .encodeToString("ops:letmein".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> allowed = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(console.url() + "api/overview"))
                            .header("Authorization", "Basic " + credentials).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, allowed.statusCode());
        }
    }

    @Test
    void consolePageIsServed() throws Exception {
        try (ZeroZDb db = seededStore(dir("console-page"));
             ConsoleServer console = ConsoleServer.builder().store("shop", db).start()) {
            String html = body(console, "");
            assertTrue(html.contains("ZeroZ DB console"), "page title");
            assertTrue(html.contains("api/browse"), "page wires the browse endpoint");
        }
    }
}
