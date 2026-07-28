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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zeroz4j.db.ZeroZDb;
import com.zeroz4j.db.net.DbQuery;
import com.zeroz4j.db.schema.SchemaCompatibility;
import com.zeroz4j.db.schema.SchemaDescriptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An operator's window into a running ZeroZ DB: health, schema, and a domain-aware data browser,
 * served as a small web page plus a JSON API.
 * <p>
 * This exists because an embedded database has no {@code psql} — no way for support staff to
 * answer "what does this record actually look like?" without a debugger. EclipseStore's own REST
 * browser walks raw object ids; this walks your field names, knows your indexes, and can diff
 * the running model against the committed schema baseline.
 * <p>
 * <strong>Safety.</strong> Off unless started. Binds loopback by default and refuses any other
 * interface without a password. Read-only: it exposes queries, never commands.
 */
public final class ConsoleServer implements AutoCloseable {

    private final Map<String, ZeroZDb> stores;
    private final Map<String, QueryCatalog> catalogs;
    private final Path schemaBaseline;
    private final List<Class<?>> modelClasses;
    private final String password;
    private final HttpServer http;
    private final Instant started = Instant.now();

    private ConsoleServer(Builder builder) {
        this.stores = Map.copyOf(builder.stores);
        this.catalogs = Map.copyOf(builder.catalogs);
        this.schemaBaseline = builder.schemaBaseline;
        this.modelClasses = List.copyOf(builder.modelClasses);
        this.password = builder.password;
        try {
            this.http = HttpServer.create(
                    new InetSocketAddress(builder.bindAddress, builder.port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot start console on "
                    + builder.bindAddress + ":" + builder.port, e);
        }
        http.createContext("/", this::route);
        http.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        http.start();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public String url() {
        return "http://127.0.0.1:" + port() + "/";
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"ZeroZ DB\"");
                send(exchange, 401, "text/plain", "authentication required");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

            if (path.equals("/") || path.equals("/index.html")) {
                send(exchange, 200, "text/html; charset=utf-8", Console.PAGE);
            } else if (path.equals("/api/overview")) {
                send(exchange, 200, "application/json", overview());
            } else if (path.equals("/api/schema")) {
                send(exchange, 200, "application/json", schema());
            } else if (path.equals("/api/browse")) {
                send(exchange, 200, "application/json", browse(query));
            } else if (path.equals("/api/queries")) {
                send(exchange, 200, "application/json", queries(query));
            } else if (path.equals("/api/run")) {
                send(exchange, 200, "application/json", run(query));
            } else {
                send(exchange, 404, "application/json", "{\"error\":\"no such endpoint\"}");
            }
        } catch (RuntimeException e) {
            send(exchange, 400, "application/json",
                    Json.value(Map.of("error", String.valueOf(e.getMessage()),
                            "type", e.getClass().getSimpleName())));
        }
    }

    private String overview() {
        List<Object> storeInfo = new ArrayList<>();
        stores.forEach((name, db) -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", name);
            info.put("commitSequence", db.commitSequence());
            info.put("rootType", db.root() == null ? null : db.root().getClass().getName());
            storeInfo.add(info);
        });
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uptime", Duration.between(started, Instant.now()).toString());
        out.put("pid", ProcessHandle.current().pid());
        out.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576);
        out.put("heapMaxMb", runtime.maxMemory() / 1_048_576);
        out.put("stores", storeInfo);
        return Json.value(out);
    }

    private String schema() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (modelClasses.isEmpty()) {
            out.put("model", null);
            out.put("note", "no model classes registered with the console");
            return Json.value(out);
        }
        SchemaDescriptor current = SchemaDescriptor.of(modelClasses);
        List<Object> classes = new ArrayList<>();
        current.classes().forEach((className, fields) -> {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("name", className);
            rendered.put("fields", fields.stream()
                    .map(f -> Map.<String, Object>of("name", f.name(), "type", f.type()))
                    .toList());
            classes.add(rendered);
        });
        out.put("classes", classes);

        if (schemaBaseline != null && Files.exists(schemaBaseline)) {
            SchemaCompatibility.Report report =
                    SchemaCompatibility.compare(SchemaDescriptor.read(schemaBaseline), current);
            out.put("baseline", schemaBaseline.toString());
            out.put("rollbackCompatible", report.isRollbackCompatible());
            out.put("changes", report.changes().stream()
                    .map(c -> Map.<String, Object>of("severity", c.severity().name(),
                            "class", c.className(), "detail", c.detail()))
                    .toList());
        }
        return Json.value(out);
    }

    private String browse(Map<String, String> query) {
        ZeroZDb db = store(query.get("store"));
        String path = query.getOrDefault("path", "");
        int offset = intParam(query, "offset", 0);
        int limit = Math.min(500, intParam(query, "limit", GraphBrowser.DEFAULT_PAGE));
        // Read under the store lock so the console can never observe a half-applied write.
        return db.read(() -> GraphBrowser.render(db.root(), path, offset, limit));
    }

    private String queries(Map<String, String> query) {
        QueryCatalog catalog = catalogs.get(query.get("store"));
        if (catalog == null) {
            return Json.value(Map.of("queries", List.of()));
        }
        return Json.value(Map.of("queries", catalog.all().stream()
                .map(entry -> Map.<String, Object>of("name", entry.name(),
                        "description", entry.description(),
                        "parameters", entry.parameters()))
                .toList()));
    }

    private String run(Map<String, String> params) {
        String storeName = params.get("store");
        ZeroZDb db = store(storeName);
        QueryCatalog catalog = catalogs.get(storeName);
        if (catalog == null) {
            throw new IllegalArgumentException("No query catalog for store " + storeName);
        }
        QueryCatalog.Entry entry = catalog.get(params.get("query"));
        if (entry == null) {
            throw new IllegalArgumentException("No such query: " + params.get("query"));
        }
        DbQuery<?> query = entry.factory().apply(params);
        Object result = db.read(() -> query.execute(db.root()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", entry.name());
        out.put("resultType", result == null ? null : result.getClass().getName());
        out.put("result", renderResult(result));
        return Json.value(out);
    }

    private static Object renderResult(Object result) {
        if (result == null || result instanceof Number || result instanceof Boolean
                || result instanceof CharSequence) {
            return result == null ? null : String.valueOf(result);
        }
        if (result instanceof java.util.Collection<?> collection) {
            return collection.stream().limit(200).map(String::valueOf).toList();
        }
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            map.forEach((k, v) -> rendered.put(String.valueOf(k), String.valueOf(v)));
            return rendered;
        }
        return String.valueOf(result);
    }

    private ZeroZDb store(String name) {
        ZeroZDb db = stores.get(name);
        if (db == null) {
            throw new IllegalArgumentException("Unknown store: " + name);
        }
        return db;
    }

    private boolean authorized(HttpExchange exchange) {
        if (password == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            return false;
        }
        String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                StandardCharsets.UTF_8);
        int colon = decoded.indexOf(':');
        String presented = colon < 0 ? decoded : decoded.substring(colon + 1);
        return java.security.MessageDigest.isEqual(
                password.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null) {
            return params;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                params.put(java.net.URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static int intParam(Map<String, String> params, String name, int fallback) {
        String value = params.get(name);
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        http.stop(0);
    }

    public static final class Builder {
        private final Map<String, ZeroZDb> stores = new LinkedHashMap<>();
        private final Map<String, QueryCatalog> catalogs = new LinkedHashMap<>();
        private final List<Class<?>> modelClasses = new ArrayList<>();
        private Path schemaBaseline;
        private String password;
        private String bindAddress = "127.0.0.1";
        private int port;

        public Builder store(String name, ZeroZDb db) {
            stores.put(Objects.requireNonNull(name), Objects.requireNonNull(db));
            return this;
        }

        /** Publishes named queries operators may run against this store. */
        public Builder queries(String store, QueryCatalog catalog) {
            catalogs.put(store, catalog);
            return this;
        }

        /** Model classes to render, and to diff against {@link #schemaBaseline}. */
        public Builder model(Class<?>... classes) {
            modelClasses.addAll(List.of(classes));
            return this;
        }

        /** Committed schema baseline, so the console can show pending incompatibilities. */
        public Builder schemaBaseline(Path baseline) {
            this.schemaBaseline = baseline;
            return this;
        }

        /** Required for any non-loopback bind. Presented over HTTP Basic. */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public ConsoleServer start() {
            if (stores.isEmpty()) {
                throw new IllegalStateException("Register at least one store");
            }
            if (!"127.0.0.1".equals(bindAddress) && !"localhost".equals(bindAddress)
                    && password == null) {
                throw new IllegalStateException("Refusing to expose the console on " + bindAddress
                        + " without a password: it can read every value in your stores.");
            }
            return new ConsoleServer(this);
        }
    }
}
