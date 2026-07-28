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
package com.zeroz4j.db.net;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A local, continuously-refreshed copy of a remote store's graph. Reads run against the local
 * heap at full speed — no round trip, no serialization — at the price of bounded staleness.
 * <p>
 * A background thread long-polls the owner ({@link ZeroZDbClient#awaitCommit}); the instant a
 * commit lands it fetches a fresh snapshot and swaps it in atomically. Readers always see one
 * internally-consistent snapshot: never a half-applied update, never a torn graph. Between
 * commits the replica is exact; after a commit it trails by roughly one round trip plus the
 * snapshot transfer.
 * <p>
 * <strong>Honest scope.</strong> Refresh ships a whole snapshot, not a diff. That is the right
 * trade for the target workload (read-heavy, modest writes) and the wrong one for a large graph
 * under constant writes — cost per refresh is O(graph), not O(change). Incremental replication
 * (shipping the commit's changed objects) is designed but not built; see the design doc.
 * <p>
 * Writes never go through a replica: send a {@link DbCommand} to the owner, then the change
 * comes back on the refresh.
 */
public final class ReplicaView<R> implements AutoCloseable {

    private final ZeroZDbClient client;
    private final String store;
    private final Thread refresher;
    private final AtomicLong refreshes = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong(-1);
    private volatile R snapshot;
    private volatile boolean closed;

    private ReplicaView(ZeroZDbClient client, String store) {
        this.client = client;
        this.store = store;
        refreshNow();
        this.refresher = Thread.ofVirtual().name("zerozdb-replica-" + store).start(this::refreshLoop);
    }

    /** Starts a replica of {@code store} fed by {@code client} (which it takes over entirely). */
    public static <R> ReplicaView<R> of(ZeroZDbClient client, String store) {
        return new ReplicaView<>(client, store);
    }

    /** Applies {@code reader} to the local snapshot. Pure heap access. */
    public <T> T read(Function<R, T> reader) {
        return reader.apply(snapshot);
    }

    /** The local snapshot itself. Treat as read-only: mutating it changes nothing on the owner. */
    public R root() {
        return snapshot;
    }

    /** The owner's commit sequence this snapshot reflects. */
    public long sequence() {
        return sequence.get();
    }

    /** How many times the local copy has been rebuilt — useful for tests and metrics. */
    public long refreshCount() {
        return refreshes.get();
    }

    /**
     * Blocks until the replica reflects at least {@code targetSequence}. For the rare read that
     * must not be stale (read-your-own-write after a command), or for tests.
     */
    public void awaitSequence(long targetSequence, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (sequence.get() < targetSequence && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void refreshLoop() {
        while (!closed) {
            try {
                long observed = client.awaitCommit(store, sequence.get(), 5_000);
                if (closed) {
                    return;
                }
                if (observed > sequence.get()) {
                    refreshNow();
                }
            } catch (RuntimeException e) {
                if (closed) {
                    return;
                }
                sleepQuietly();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void refreshNow() {
        R fresh = (R) client.query(store, new SnapshotQuery());
        long observed = client.awaitCommit(store, -1, 0);   // sequence as of this snapshot
        this.snapshot = fresh;
        this.sequence.set(observed);
        this.refreshes.incrementAndGet();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        closed = true;
        refresher.interrupt();
        client.close();
    }

    /** Convenience: a replica with its own dedicated connection to the given server. */
    public static <R> ReplicaView<R> connect(String host, int port, String schemaId, String store) {
        return of(ZeroZDbClient.connect(host, port, schemaId), store);
    }

    /** For an owner JVM: a "replica" that is simply the live local graph, for API symmetry. */
    public static <R> LocalView<R> local(Supplier<R> root) {
        return new LocalView<>(root);
    }

    /** The owner-side counterpart of a replica: reads hit the live graph directly. */
    public static final class LocalView<R> {
        private final Supplier<R> root;

        private LocalView(Supplier<R> root) {
            this.root = root;
        }

        public <T> T read(Function<R, T> reader) {
            return reader.apply(root.get());
        }
    }
}
