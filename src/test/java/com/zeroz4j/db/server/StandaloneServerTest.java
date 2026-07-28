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
package com.zeroz4j.db.server;

import org.junit.jupiter.api.Test;
import com.zeroz4j.db.net.Commands;
import com.zeroz4j.db.net.Endpoint;
import com.zeroz4j.db.net.ZeroZDbClient;
import com.zeroz4j.db.net.ZeroZDbNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real daemon as its own process, exactly as an operator would. */
class StandaloneServerTest {

    @Test
    void daemonServesConfiguredStoresAndShutsDownCleanly() throws Exception {
        Path base = Path.of("target", "daemon-" + System.nanoTime());
        Path storeA = base.resolve("shop");
        Path storeB = base.resolve("crm");
        Files.createDirectories(base);

        Path configFile = base.resolve("server.properties");
        Files.writeString(configFile, """
                port = 0
                schemaId = daemon-v1
                durability = OS_BUFFERED
                store.shop.dir = %s
                store.shop.root = com.zeroz4j.db.TestRoot
                store.crm.dir = %s
                store.crm.root = com.zeroz4j.db.TestRoot
                """.formatted(storeA.toString().replace("\\", "/"),
                storeB.toString().replace("\\", "/")));

        Process daemon = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                ZeroZDbServerMain.class.getName(), configFile.toString())
                .redirectErrorStream(true)
                .start();

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(daemon.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Exception ignored) {
            }
        });

        try {
            String ready = awaitLine(lines, "listening on port", 90);
            assertNotNull(ready, "daemon never reported readiness");

            // Clients find it through the endpoint file the daemon published — no port needed.
            Endpoint endpoint = Endpoint.read(storeA);
            assertNotNull(endpoint, "daemon publishes an endpoint beside each store");
            assertEquals("daemon-v1", endpoint.schemaId());

            try (ZeroZDbClient client = ZeroZDbClient.connect(
                    endpoint.host(), endpoint.port(), "daemon-v1")) {
                assertEquals(java.util.Set.of("shop", "crm"), client.stores());
                client.execute("shop", new Commands.Put("k", "shop-value"));
                client.execute("crm", new Commands.Put("k", "crm-value"));
                assertEquals("shop-value", client.query("shop", new Commands.Get("k")));
                assertEquals("crm-value", client.query("crm", new Commands.Get("k")),
                        "stores are independent");
            }

            // An app node in CLIENT_ONLY mode joins the daemon with no port configuration at all.
            try (ZeroZDbNode app = ZeroZDbNode.builder(storeA, com.zeroz4j.db.TestRoot::new)
                    .mode(ZeroZDbNode.Mode.CLIENT_ONLY)
                    .storeName("shop")
                    .schemaId("daemon-v1")
                    .build()) {
                assertFalse(app.isOwner(), "app node must not own data served by the daemon");
                assertEquals("shop-value", app.query(new Commands.Get("k")));
            }
        } finally {
            // Closing stdin asks for a graceful stop. (On Unix, SIGTERM does the same through
            // the shutdown hook; on Windows Process.destroy() is a hard kill and hooks never
            // run, which is why the daemon also watches stdin.)
            daemon.getOutputStream().close();
            if (!daemon.waitFor(60, TimeUnit.SECONDS)) {
                daemon.destroyForcibly();
                throw new AssertionError("daemon did not exit after stdin close");
            }
        }

        assertNotNull(awaitLine(lines, "stopped", 30), "daemon did not log a clean shutdown");
        assertTrue(Endpoint.read(storeA) == null && Endpoint.read(storeB) == null,
                "endpoints withdrawn on shutdown");

        // Data written through the daemon is durable and readable by a plain embedded node.
        try (ZeroZDbNode reopened = ZeroZDbNode.embedded(storeA, com.zeroz4j.db.TestRoot::new)) {
            assertEquals("shop-value", reopened.query(new Commands.Get("k")));
        }
    }

    private static String awaitLine(BlockingQueue<String> lines, String contains, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(2, TimeUnit.SECONDS);
            if (line != null && line.contains(contains)) {
                return line;
            }
        }
        return null;
    }
}
