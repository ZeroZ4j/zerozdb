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

import com.zeroz4j.db.net.DbQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Named, parameterised queries an application publishes for operators to run from the console.
 * <p>
 * This is the deliberate answer to "no query language": support staff get the "look up this
 * customer" capability a SQL console would give them, but only through queries the application
 * has written, reviewed and named — no arbitrary expressions evaluated against live data.
 *
 * <pre>{@code
 * catalog.register("customer-by-email", "Find a customer by email address",
 *         List.of("email"), params -> new FindCustomerByEmail(params.get("email")));
 * }</pre>
 */
public final class QueryCatalog {

    public record Entry(String name, String description, List<String> parameters,
                        Function<Map<String, String>, DbQuery<?>> factory) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public QueryCatalog register(String name, String description, List<String> parameters,
                                 Function<Map<String, String>, DbQuery<?>> factory) {
        entries.put(name, new Entry(name, description, List.copyOf(parameters), factory));
        return this;
    }

    public QueryCatalog register(String name, String description, DbQuery<?> query) {
        return register(name, description, List.of(), params -> query);
    }

    public Entry get(String name) {
        return entries.get(name);
    }

    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
