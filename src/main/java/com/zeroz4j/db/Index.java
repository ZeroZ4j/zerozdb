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

import java.util.List;
import java.util.Set;

/**
 * A library-maintained secondary index over a source Map or Collection of entities: O(1)
 * lookups instead of stream scans, kept correct automatically at every commit. Membership
 * changes are detected by diffing the enlisted source structure against the index's own last
 * committed state (so mutate-then-store and edit-then-mutate both work); key changes are
 * detected on enlisted entities. Reads are safe from any thread and reflect the last committed
 * state.
 */
public interface Index<K, V> {

    String name();

    /** All members with this key, as an immutable snapshot. Empty list if none. */
    List<V> get(K key);

    boolean contains(K key);

    Set<K> keys();

    /** Number of indexed members. */
    int size();
}
