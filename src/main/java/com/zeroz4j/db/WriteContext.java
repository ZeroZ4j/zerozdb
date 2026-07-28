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

/**
 * Handed to a write-block. Mutate plain objects freely inside the block, then enlist every
 * changed object with {@link #store(Object)} — the EclipseStore habit, unchanged. All enlisted
 * objects are flushed in one atomic, durable commit when the outermost block exits normally.
 * <p>
 * A context is only valid inside its block; escaping it and calling {@code store} later throws.
 */
public interface WriteContext {

    /**
     * Enlists a changed object for the commit at block exit. Following EclipseStore semantics,
     * storing an object does not cascade into already-persisted referenced objects — enlist
     * every changed nesting level explicitly.
     */
    void store(Object object);

    default void storeAll(Object... objects) {
        for (Object o : objects) {
            store(o);
        }
    }

    /**
     * Enlists an object <em>before</em> you mutate it, guaranteeing a faithful rollback
     * snapshot. Same enlistment as {@link #store(Object)} — the difference is timing: calling
     * {@code store} only after mutating snapshots the already-mutated state, which rollback
     * then cannot undo for that first change.
     */
    default void edit(Object object) {
        store(object);
    }

    /**
     * Registers a compensation to run (newest-first) if this write-block fails. For undoing
     * effects the automatic before-images cannot cover.
     */
    void onRollback(Runnable undo);

    /**
     * Like {@link #store(Object)}, but the commit fails with {@link StaleObjectException} if the
     * object was committed by anyone after {@code baseline} was captured (via
     * {@link ZeroZDb#baseline(Object)}, typically when the edit began — e.g. at form-open).
     * Opt-in per call site; plain {@code store} remains last-write-wins.
     */
    void storeChecked(Object object, long baseline);
}
