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

import java.util.Set;

/**
 * An {@link Index} whose keys are enforced unique: a commit that would create a duplicate key
 * fails with {@link UniqueConstraintException} before anything reaches disk, and the
 * write-block rolls back. A real database constraint, in pure Java.
 */
public interface UniqueIndex<K, V> {

    String name();

    /** The single member with this key, or {@code null} if absent. */
    V get(K key);

    boolean contains(K key);

    Set<K> keys();

    int size();
}
