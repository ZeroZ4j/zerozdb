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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    @Test
    void serverRefusesToListenOffLoopbackWithoutASecret() {
        Path storeDir = dir("sec-bind");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir)) {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> ZeroZDbServer.builder().store("main", db)
                            .bindAddress("0.0.0.0").start());
            assertTrue(refusal.getMessage().contains("without a secret"), refusal.getMessage());
        }
    }

    @Test
    void clientWithoutTheSecretIsRejected() {
        Path storeDir = dir("sec-nosecret");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).secret("s3cret").start()) {

            assertThrows(AuthenticationException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(), "default"));
            assertThrows(AuthenticationException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(), "default", "wrong"));
            assertEquals(2, server.connectionsRejected());
        }
    }

    @Test
    void clientWithTheSecretIsServed() {
        Path storeDir = dir("sec-secret");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db).secret("s3cret").start();
             ZeroZDbClient client = ZeroZDbClient.connect(
                     "127.0.0.1", server.port(), "default", "s3cret")) {
            client.execute("main", new Commands.Put("k", "v"));
            assertEquals("v", client.query("main", new Commands.Get("k")));
        }
    }

    @Test
    void rejectionDoesNotDiscloseTheStoreInventoryOrWhichCheckFailed() {
        Path storeDir = dir("sec-quiet");
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), storeDir);
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("secret-store-name", db).secret("s3cret").start()) {

            AuthenticationException failure = assertThrows(AuthenticationException.class,
                    () -> ZeroZDbClient.connect("127.0.0.1", server.port(), "default", "wrong"));
            assertFalse(failure.getMessage().contains("secret-store-name"),
                    "an unauthenticated caller must not learn what stores exist");
            assertFalse(failure.getMessage().contains("s3cret"),
                    "the expected secret must never appear in a message");
        }
    }

    @Test
    void tlsProtectsTheWireAndStillRequiresTheSecret() throws Exception {
        Path base = dir("sec-tls");
        Files.createDirectories(base);
        Path keystore = base.resolve("server.p12");
        generateKeystore(keystore);

        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), base.resolve("store"));
             ZeroZDbServer server = ZeroZDbServer.builder()
                     .store("main", db)
                     .secret("s3cret")
                     .tls(Tls.server(keystore, "changeit".toCharArray()))
                     .start()) {

            javax.net.ssl.SSLContext clientContext = Tls.client(keystore, "changeit".toCharArray());

            try (ZeroZDbClient client = ZeroZDbClient.connect(
                    "127.0.0.1", server.port(), "default", "s3cret", clientContext)) {
                client.execute("main", new Commands.Put("k", "over-tls"));
                assertEquals("over-tls", client.query("main", new Commands.Get("k")));
            }

            assertThrows(RuntimeException.class, () -> ZeroZDbClient.connect(
                    "127.0.0.1", server.port(), "default", "wrong", clientContext),
                    "TLS does not replace authentication");
        }
    }

    /** Self-signed cert via the JDK's keytool — no crypto library needed for the test. */
    private static void generateKeystore(Path keystore) throws Exception {
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "zerozdb", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keystore.toString(),
                "-storepass", "changeit", "-keypass", "changeit",
                "-dname", "CN=localhost", "-validity", "1",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        assertTrue(keytool.waitFor(120, TimeUnit.SECONDS), "keytool did not finish");
        assertEquals(0, keytool.exitValue(), "keytool failed: "
                + new String(keytool.getInputStream().readAllBytes()));
    }
}
