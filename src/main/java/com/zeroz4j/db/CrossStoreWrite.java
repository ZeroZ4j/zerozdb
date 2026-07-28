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

import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An atomic-intent write across several stores: all write locks acquired in a total order (by
 * store path — two concurrent cross-writes over overlapping stores can never deadlock), the
 * block runs, then phase 1 validates every participant (version checks, unique constraints)
 * while all locks are held, and only then does phase 2 commit each store.
 * <p>
 * <strong>Honest limit — this is not distributed 2PC.</strong> Every failure phase 1 can detect
 * aborts everything cleanly (memory rolled back, nothing on any disk). But N local EclipseStore
 * commits cannot be made atomic against process death or an I/O failure <em>between</em>
 * phase-2 commits: in that case already-committed stores keep their (self-consistent) state,
 * uncommitted participants roll back, and the cross-store invariant is torn — a documented,
 * repairable condition, not a hidden one. Use for rare coordinated operations (provisioning),
 * not as a routine transaction mechanism.
 */
public final class CrossStoreWrite {

    public static void run(Consumer<CrossStoreContext> block, ZeroZDb... stores) {
        runResult(ctx -> {
            block.accept(ctx);
            return null;
        }, stores);
    }

    public static <T> T runResult(Function<CrossStoreContext, T> block, ZeroZDb... stores) {
        if (stores.length == 0) {
            throw new IllegalArgumentException("At least one store required");
        }
        ZeroZDb[] participants = Arrays.stream(stores)
                .distinct()
                .sorted(Comparator.comparing(ZeroZDb::orderingKey))
                .toArray(ZeroZDb[]::new);

        LinkedHashMap<ZeroZDb, WriteContextImpl> contexts = new LinkedHashMap<>();
        Map<ZeroZDb, Boolean> committed = new IdentityHashMap<>();
        try {
            for (ZeroZDb db : participants) {
                contexts.put(db, db.beginCross());
            }
            T result = block.apply(store -> {
                WriteContextImpl ctx = contexts.get(store);
                if (ctx == null) {
                    throw new IllegalArgumentException(
                            "Store is not a participant of this cross-store write: " + store.orderingKey());
                }
                return ctx;
            });

            LinkedHashMap<ZeroZDb, List<Runnable>> plans = new LinkedHashMap<>();
            for (Map.Entry<ZeroZDb, WriteContextImpl> e : contexts.entrySet()) {
                if (!e.getValue().dirty.isEmpty()) {
                    plans.put(e.getKey(), e.getKey().prepareFlush(e.getValue()));
                }
            }
            for (Map.Entry<ZeroZDb, List<Runnable>> e : plans.entrySet()) {
                e.getKey().commitFlush(contexts.get(e.getKey()), e.getValue());
                committed.put(e.getKey(), Boolean.TRUE);
            }
            return result;
        } catch (RuntimeException | Error e) {
            for (Map.Entry<ZeroZDb, WriteContextImpl> entry : contexts.entrySet()) {
                if (!committed.containsKey(entry.getKey())) {
                    entry.getValue().rollback();
                }
            }
            throw e;
        } finally {
            List<Map.Entry<ZeroZDb, WriteContextImpl>> begun = List.copyOf(contexts.entrySet());
            for (int i = begun.size() - 1; i >= 0; i--) {
                begun.get(i).getKey().endCross(begun.get(i).getValue());
            }
        }
    }

    private CrossStoreWrite() {
    }
}
