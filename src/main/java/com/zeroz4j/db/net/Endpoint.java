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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The rendezvous file a store's owner writes beside its data so later JVMs can find it. Written
 * only while the ownership lock is held, and deleted on clean shutdown; a stale file is
 * harmless because a client that cannot connect falls back to trying to take ownership itself.
 */
public record Endpoint(String host, int port, String schemaId, long pid) {

    static final String FILE_NAME = "zerozdb.endpoint";

    public static void write(Path storeDir, Endpoint endpoint) {
        Properties properties = new Properties();
        properties.setProperty("host", endpoint.host());
        properties.setProperty("port", Integer.toString(endpoint.port()));
        properties.setProperty("schemaId", endpoint.schemaId());
        properties.setProperty("pid", Long.toString(endpoint.pid()));
        Path target = storeDir.resolve(FILE_NAME);
        Path temp = storeDir.resolve(FILE_NAME + ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) {
                properties.store(out, "ZeroZ DB server endpoint");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot publish endpoint for store " + storeDir, e);
        }
    }

    public static Endpoint read(Path storeDir) {
        Path file = storeDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        String port = properties.getProperty("port");
        if (port == null) {
            return null;
        }
        return new Endpoint(
                properties.getProperty("host", "127.0.0.1"),
                Integer.parseInt(port),
                properties.getProperty("schemaId", "default"),
                Long.parseLong(properties.getProperty("pid", "-1")));
    }

    public static void delete(Path storeDir) {
        try {
            Files.deleteIfExists(storeDir.resolve(FILE_NAME));
        } catch (IOException ignored) {
        }
    }
}
