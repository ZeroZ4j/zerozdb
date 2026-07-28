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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Engine-side index implementation. All mutation happens under the store's write lock, as part
 * of commit: {@link #prepare} computes and validates the operations (throwing before anything
 * is persisted on a unique violation), and the returned ops are applied only after the storage
 * commit succeeds — so a failed block never touches the index.
 */
final class IndexImpl<K, V> implements Index<K, V> {

    private final ZeroZDb db;
    private final String name;
    private final Class<V> type;
    private final Supplier<?> source;
    private final Function<V, K> keyFn;
    private final boolean unique;

    private final HashMap<K, Set<V>> byKey = new HashMap<>();
    private final IdentityHashMap<V, K> keyOf = new IdentityHashMap<>();

    IndexImpl(ZeroZDb db, String name, Class<V> type, Supplier<?> source,
              Function<V, K> keyFn, boolean unique) {
        this.db = db;
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.source = Objects.requireNonNull(source, "source");
        this.keyFn = Objects.requireNonNull(keyFn, "keyFn");
        this.unique = unique;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<V> get(K key) {
        return db.read(() -> {
            Set<V> members = byKey.get(key);
            return members == null ? List.of() : List.copyOf(members);
        });
    }

    @Override
    public boolean contains(K key) {
        return db.read(() -> byKey.containsKey(key));
    }

    @Override
    public Set<K> keys() {
        return db.read(() -> new HashSet<>(byKey.keySet()));
    }

    @Override
    public int size() {
        return db.read(keyOf::size);
    }

    UniqueIndex<K, V> asUnique() {
        return new UniqueIndex<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public V get(K key) {
                return db.read(() -> {
                    Set<V> members = byKey.get(key);
                    return members == null || members.isEmpty() ? null : members.iterator().next();
                });
            }

            @Override
            public boolean contains(K key) {
                return IndexImpl.this.contains(key);
            }

            @Override
            public Set<K> keys() {
                return IndexImpl.this.keys();
            }

            @Override
            public int size() {
                return IndexImpl.this.size();
            }
        };
    }

    /** Full scan of the source; caller holds the write lock. */
    void rebuild() {
        byKey.clear();
        keyOf.clear();
        for (V member : currentMembers()) {
            addDirect(member, keyFn.apply(member));
        }
    }

    /**
     * Computes this index's operations for the committing transaction: membership adds/removes
     * from the source structure's before-image diff, key moves from enlisted entities. Unique
     * violations throw here — before the storage commit.
     */
    void prepare(Set<Object> dirty, Map<Object, Object> snapshots, List<Runnable> ops) {
        Object src = source.get();
        Collection<V> current = currentMembers();

        Set<V> added = newIdentitySet();
        Set<V> removed = newIdentitySet();
        if (snapshots.containsKey(src)) {
            // The source structure was enlisted, so membership may have changed. Diff its
            // current contents against this index's own membership (the last committed state) —
            // correct regardless of whether the caller mutated before or after enlisting.
            Set<Object> currentSet = identitySetOf(current);
            for (V v : current) {
                if (!keyOf.containsKey(v)) {
                    added.add(v);
                }
            }
            for (V v : keyOf.keySet()) {
                if (!currentSet.contains(v)) {
                    removed.add(v);
                }
            }
        }

        List<Object[]> moves = new ArrayList<>();
        for (Object o : dirty) {
            if (!type.isInstance(o)) {
                continue;
            }
            V v = type.cast(o);
            if (added.contains(v) || removed.contains(v) || !keyOf.containsKey(v)) {
                continue;
            }
            K oldKey = keyOf.get(v);
            K newKey = keyFn.apply(v);
            if (!Objects.equals(oldKey, newKey)) {
                moves.add(new Object[]{v, oldKey, newKey});
            }
        }

        if (added.isEmpty() && removed.isEmpty() && moves.isEmpty()) {
            return;
        }
        if (unique) {
            validateUnique(added, removed, moves);
        }
        for (V v : removed) {
            ops.add(() -> removeDirect(v));
        }
        for (Object[] move : moves) {
            @SuppressWarnings("unchecked") V v = (V) move[0];
            @SuppressWarnings("unchecked") K oldKey = (K) move[1];
            @SuppressWarnings("unchecked") K newKey = (K) move[2];
            ops.add(() -> {
                removeEntry(v, oldKey);
                addDirect(v, newKey);
            });
        }
        for (V v : added) {
            ops.add(() -> addDirect(v, keyFn.apply(v)));
        }
    }

    private void validateUnique(Set<V> added, Set<V> removed, List<Object[]> moves) {
        Set<Object> leaving = newIdentitySetRaw();
        leaving.addAll(removed);
        for (Object[] move : moves) {
            leaving.add(move[0]);
        }
        Set<K> claimed = new HashSet<>();
        List<Object[]> candidates = new ArrayList<>();
        for (V v : added) {
            candidates.add(new Object[]{v, keyFn.apply(v)});
        }
        for (Object[] move : moves) {
            candidates.add(new Object[]{move[0], move[2]});
        }
        for (Object[] candidate : candidates) {
            @SuppressWarnings("unchecked") V v = (V) candidate[0];
            @SuppressWarnings("unchecked") K key = (K) candidate[1];
            if (!claimed.add(key)) {
                throw new UniqueConstraintException(name, key);
            }
            Set<V> occupants = byKey.get(key);
            if (occupants != null) {
                for (V occupant : occupants) {
                    if (occupant != v && !leaving.contains(occupant)) {
                        throw new UniqueConstraintException(name, key);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<V> currentMembers() {
        Object src = source.get();
        if (src instanceof Map<?, ?> m) {
            return (Collection<V>) m.values();
        }
        if (src instanceof Collection<?> c) {
            return (Collection<V>) c;
        }
        throw new IllegalStateException("Index '" + name + "': source must be a Map or Collection, got "
                + (src == null ? "null" : src.getClass().getName()));
    }

    private void addDirect(V v, K key) {
        Set<V> members = byKey.computeIfAbsent(key, k -> newIdentitySet());
        if (unique && !members.isEmpty() && !members.contains(v)) {
            throw new UniqueConstraintException(name, key);
        }
        members.add(v);
        keyOf.put(v, key);
    }

    private void removeDirect(V v) {
        if (keyOf.containsKey(v)) {
            K key = keyOf.remove(v);
            removeEntry(v, key);
        }
    }

    private void removeEntry(V v, K key) {
        Set<V> members = byKey.get(key);
        if (members != null) {
            members.remove(v);
            if (members.isEmpty()) {
                byKey.remove(key);
            }
        }
    }

    private Set<V> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private Set<Object> newIdentitySetRaw() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static Set<Object> identitySetOf(Collection<?> collection) {
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(collection);
        return set;
    }
}
