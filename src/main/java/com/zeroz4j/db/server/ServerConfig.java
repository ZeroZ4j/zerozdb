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

import com.zeroz4j.db.Durability;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Configuration for the standalone server, read from a properties file.
 *
 * <pre>
 * port        = 5150
 * schemaId    = myapp-v3
 * durability  = SYNC            # or OS_BUFFERED
 *
 * store.shop.dir  = /data/shop
 * store.shop.root = com.example.ShopRoot
 * store.crm.dir   = /data/crm
 * store.crm.root  = com.example.CrmRoot
 * </pre>
 *
 * Root classes are instantiated reflectively and must have a public no-arg constructor; they are
 * used only when a store is empty. The domain jar must be on the server's classpath — the server
 * executes your commands and maintains your indexes, so it is versioned with your model.
 */
public record ServerConfig(int port, String schemaId, Durability durability,
                           List<StoreConfig> stores, String bindAddress, String secret,
                           int consolePort, String consolePassword) {

    public record StoreConfig(String name, Path directory, String rootClassName) {

        public Object newRoot() {
            try {
                return Class.forName(rootClassName).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot instantiate root class '" + rootClassName
                        + "' for store '" + name + "'. It needs a public no-arg constructor and "
                        + "must be on the server classpath.", e);
            }
        }
    }

    public static ServerConfig load(Path file) {
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read server config " + file, e);
        }
        return of(properties);
    }

    public static ServerConfig of(Properties properties) {
        List<StoreConfig> stores = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("store.") && key.endsWith(".dir")) {
                String name = key.substring("store.".length(), key.length() - ".dir".length());
                String dir = properties.getProperty(key);
                String root = properties.getProperty("store." + name + ".root");
                if (root == null) {
                    throw new IllegalArgumentException(
                            "Missing store." + name + ".root for store '" + name + "'");
                }
                stores.add(new StoreConfig(name, Path.of(dir), root));
            }
        }
        if (stores.isEmpty()) {
            throw new IllegalArgumentException(
                    "No stores configured. Add store.<name>.dir and store.<name>.root entries.");
        }
        stores.sort(java.util.Comparator.comparing(StoreConfig::name));
        return new ServerConfig(
                Integer.parseInt(properties.getProperty("port", "5150")),
                properties.getProperty("schemaId", "default"),
                Durability.valueOf(properties.getProperty("durability", "SYNC")),
                List.copyOf(stores),
                properties.getProperty("bindAddress", "127.0.0.1"),
                properties.getProperty("secret"),
                // Absent or 0 means the console is not started at all.
                Integer.parseInt(properties.getProperty("console.port", "0")),
                properties.getProperty("console.password"));
    }
}
