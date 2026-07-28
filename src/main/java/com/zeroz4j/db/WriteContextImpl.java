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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The engine's write-block state: dirty set, before-images, version checks, undo log.
 * Package-private so the cross-store coordinator can drive the same machinery.
 */
final class WriteContextImpl implements WriteContext {

    final Set<Object> dirty = Collections.newSetFromMap(new IdentityHashMap<>());
    final IdentityHashMap<Object, Object> snapshots = new IdentityHashMap<>();
    final IdentityHashMap<Object, Long> versionChecks = new IdentityHashMap<>();
    final List<Runnable> undoActions = new ArrayList<>();
    boolean open = true;
    /** Set when a nested transaction rolls back, so the outermost commit refuses to proceed. */
    boolean rollbackOnly;

    @Override
    public void store(Object object) {
        checkUsable();
        if (object != null && dirty.add(object)) {
            snapshots.put(object, Snapshots.capture(object));
        }
    }

    @Override
    public void onRollback(Runnable undo) {
        checkUsable();
        undoActions.add(Objects.requireNonNull(undo, "undo"));
    }

    @Override
    public void storeChecked(Object object, long baseline) {
        store(Objects.requireNonNull(object, "object"));
        versionChecks.put(object, baseline);
    }

    void rollback() {
        for (int i = undoActions.size() - 1; i >= 0; i--) {
            undoActions.get(i).run();
        }
        snapshots.forEach(Snapshots::restore);
    }

    private void checkUsable() {
        if (!open) {
            throw new IllegalStateException(
                    "WriteContext used outside its write-block; do not let the context escape");
        }
    }
}
