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
import com.zeroz4j.db.schema.SchemaDescriptor;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The switchover case: a daemon serving shared stores while two application versions are live.
 */
class BlueGreenSchemaTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    private static final SchemaDescriptor BLUE = SchemaDescriptor.parse("""
            app.Root name java.lang.String
            """);
    private static final SchemaDescriptor GREEN_ADDITIVE = SchemaDescriptor.parse("""
            app.Root name java.lang.String
            app.Root nickname java.lang.String
            """);
    private static final SchemaDescriptor GREEN_BREAKING = SchemaDescriptor.parse("""
            app.Root nickname java.lang.String
            """);

    @Test
    void perStoreSchemaIdsLetOneStoreMoveWithoutLockingClientsOutOfTheOthers() {
        Path rootDir = dir("bg-root");
        Path templateDir = dir("bg-templates");
        try (ZeroZDb root = ZeroZDb.open(new TestRoot(), rootDir);
             ZeroZDb templates = ZeroZDb.open(new TestRoot(), templateDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("root", root, StoreSchema.of("root-v3"))
                     .store("templates", templates, StoreSchema.of("templates-v7"))
                     .start()) {

            // A build that knows root-v3 but an older templates model keeps using root.
            try (ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", server.port(),
                    ConnectOptions.create()
                            .storeSchemaId("root", "root-v3")
                            .storeSchemaId("templates", "templates-v6"))) {

                assertEquals(java.util.Set.of("root"), client.stores());
                assertTrue(client.refusedStores().containsKey("templates"));
                client.execute("root", new Commands.Put("k", "v"));
                assertThrows(IllegalArgumentException.class,
                        () -> client.query("templates", new Commands.Size()),
                        "a refused store must stay unusable, not fail silently");
            }
        }
    }

    @Test
    void additiveModelIsAdmittedSoBothReleasesRunAtOnce() {
        Path storeDir = dir("bg-additive");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("root", db, StoreSchema.of("root-v3", BLUE))
                     .schemaPolicy(SchemaPolicy.ADDITIVE_COMPATIBLE)
                     .start()) {

            // Blue: exact match.
            try (ZeroZDbClient blue = ZeroZDbClient.connect("127.0.0.1", server.port(),
                    ConnectOptions.create().schemaId("root-v3").descriptor(BLUE))) {
                blue.execute("root", new Commands.Put("from", "blue"));
            }

            // Green: different version label, but the model only adds a field.
            try (ZeroZDbClient green = ZeroZDbClient.connect("127.0.0.1", server.port(),
                    ConnectOptions.create().schemaId("root-v4").descriptor(GREEN_ADDITIVE))) {
                assertTrue(green.stores().contains("root"),
                        "an additive model must be admitted during a switchover: "
                                + green.refusedStores());
                assertEquals("blue", green.query("root", new Commands.Get("from")));
                green.execute("root", new Commands.Put("from", "green"));
            }

            // And blue still works after green has written — both versions live at once.
            try (ZeroZDbClient blue = ZeroZDbClient.connect("127.0.0.1", server.port(),
                    ConnectOptions.create().schemaId("root-v3").descriptor(BLUE))) {
                assertEquals("green", blue.query("root", new Commands.Get("from")));
            }
        }
    }

    @Test
    void breakingModelIsStillRefused() {
        Path storeDir = dir("bg-breaking");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("root", db, StoreSchema.of("root-v3", BLUE))
                     .schemaPolicy(SchemaPolicy.ADDITIVE_COMPATIBLE)
                     .start()) {

            SchemaMismatchException refusal = assertThrows(SchemaMismatchException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(),
                            ConnectOptions.create().schemaId("root-v4")
                                    .descriptor(GREEN_BREAKING)));
            assertTrue(refusal.getMessage().contains("root-v4"), refusal.getMessage());
        }
    }

    @Test
    void exactPolicyRefusesEvenAnAdditiveModel() {
        Path storeDir = dir("bg-exact");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("root", db, StoreSchema.of("root-v3", BLUE))
                     .schemaPolicy(SchemaPolicy.EXACT)
                     .start()) {

            assertThrows(SchemaMismatchException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(),
                            ConnectOptions.create().schemaId("root-v4")
                                    .descriptor(GREEN_ADDITIVE)));
        }
    }
}
