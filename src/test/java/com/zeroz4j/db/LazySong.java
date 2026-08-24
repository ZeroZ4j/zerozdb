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
package com.zeroz4j.db;

import org.eclipse.serializer.reference.Lazy;

import java.util.ArrayList;
import java.util.List;

/**
 * An entity with a small identifying part and an unbounded part behind a {@link Lazy} reference —
 * the shape the guide recommends for a collection that grows without limit. The title is what a
 * listing or an index needs; the entries are what nobody wants resident until they are opened.
 */
public class LazySong {

    public String title;
    public Lazy<List<String>> entries = Lazy.Reference(new ArrayList<>());

    public LazySong() {
    }

    public LazySong(String title) {
        this.title = title;
    }

    /** Materialises the entries, loading them from disk on first use. */
    public List<String> entries() {
        return entries.get();
    }
}
