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

import org.eclipse.serializer.afs.types.ADirectory;
import org.eclipse.serializer.persistence.types.Storer;
import org.eclipse.store.afs.nio.types.NioFileSystem;
import org.eclipse.store.afs.nio.types.NioIoHandler;
import org.eclipse.store.storage.embedded.types.EmbeddedStorage;
import org.eclipse.store.storage.embedded.types.EmbeddedStorageManager;

import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ZeroZ DB — a zero-impedance database engine over one EclipseStore store.
 * <p>
 * One instance per store. Reads run concurrently under a shared lock and are plain heap access.
 * Writes are serialized write-blocks: mutate plain objects, enlist them via
 * {@link WriteContext#store(Object)}, and everything flushes in one atomic commit — durable on
 * disk before {@code write} returns. Uncommitted state is never visible to readers, because the
 * write lock spans mutation and flush.
 * <p>
 * If a write-block throws, nothing is persisted and in-memory state is rolled back from
 * before-images captured at enlistment ({@link WriteContext#store(Object)}). Caveat: a mutation
 * made <em>before</em> its object was enlisted is baked into the snapshot — enlist first via
 * {@link WriteContext#edit(Object)} when faithful rollback matters.
 */
public final class ZeroZDb implements AutoCloseable {

    private final EmbeddedStorageManager storage;
    private final StoreOwnership ownership;
    private final boolean ownsManager;
    /**
     * Fair ordering is deliberate. Measured under 48 concurrent clients: with the default
     * barging (unfair) lock, a write-saturated load starved readers to ~31 reads/s while
     * writers ran at 3.6k/s. For a read-heavy database that is the wrong failure mode — a
     * dashboard must not stall behind a write burst. Fairness costs some write throughput and
     * buys bounded read latency.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final ThreadLocal<WriteContextImpl> currentWrite = new ThreadLocal<>();
    private final List<CommitListener> commitListeners = new CopyOnWriteArrayList<>();
    private final List<IndexImpl<?, ?>> indexes = new CopyOnWriteArrayList<>();

    /**
     * Version side table: objects enter it on first {@link #baseline}, and only tracked objects
     * pay the bump cost at commit — untracked objects (the vast majority) cost nothing.
     * Guarded by its own monitor so baselines can be taken under the shared read lock.
     */
    private final IdentityHashMap<Object, Long> versions = new IdentityHashMap<>();
    private long commitSequence;
    private volatile boolean closed;

    private ZeroZDb(EmbeddedStorageManager storage, StoreOwnership ownership, boolean ownsManager) {
        this.storage = storage;
        this.ownership = ownership;
        this.ownsManager = ownsManager;
    }

    /**
     * Opens the store in {@code directory} with {@link Durability#SYNC}, taking exclusive
     * ownership (a second opener — any process, any JVM — gets {@link StoreOwnedException}).
     * {@code root} is used when the store is empty; on reopen the persisted root is loaded —
     * always access it via {@link #root()}.
     */
    public static ZeroZDb open(Object root, Path directory) {
        return open(root, directory, Durability.SYNC);
    }

    public static ZeroZDb open(Object root, Path directory, Durability durability) {
        return open(root, directory, durability, com.zeroz4j.db.schema.SchemaEvolution.strict());
    }

    /**
     * Opens with an explicit schema-evolution policy — how data written by older versions of
     * your classes is mapped onto the current ones. See
     * {@link com.zeroz4j.db.schema.SchemaEvolution}.
     */
    public static ZeroZDb open(Object root, Path directory, Durability durability,
                               com.zeroz4j.db.schema.SchemaEvolution evolution) {
        StoreOwnership ownership = StoreOwnership.acquire(directory);
        try {
            return new ZeroZDb(startStorage(root, directory, durability, evolution),
                    ownership, true);
        } catch (RuntimeException | Error e) {
            ownership.release();
            throw e;
        }
    }

    private static EmbeddedStorageManager startStorage(
            Object root, Path directory, Durability durability,
            com.zeroz4j.db.schema.SchemaEvolution evolution) {
        org.eclipse.store.storage.embedded.types.EmbeddedStorageFoundation<?> foundation;
        if (durability == Durability.SYNC) {
            NioFileSystem fileSystem = NioFileSystem.New(SyncedIo.wrap(NioIoHandler.New()));
            ADirectory storageDirectory = fileSystem.ensureDirectoryPath(
                    fileSystem.resolvePath(directory.toAbsolutePath().toString()));
            foundation = EmbeddedStorage.Foundation(storageDirectory);
        } else {
            foundation = EmbeddedStorage.Foundation(directory);
        }
        evolution.applyTo(foundation);
        foundation.setRoot(root);
        return foundation.start();
    }

    /**
     * Opens the store <em>without</em> taking the built-in ownership lock, because the caller
     * already holds an exclusive claim on it — an {@code OwnershipArbiter} lease, typically.
     * Taking the lock twice in one JVM would collide with itself.
     * <p>
     * The caller guarantees exclusivity. Opening a store two JVMs are writing corrupts it.
     */
    public static ZeroZDb openUnguarded(Object root, Path directory, Durability durability) {
        return new ZeroZDb(startStorage(root, directory, durability,
                com.zeroz4j.db.schema.SchemaEvolution.strict()), null, true);
    }

    /**
     * Attaches to a storage manager whose lifecycle (and ownership guarding) the caller manages.
     */
    public static ZeroZDb attach(EmbeddedStorageManager storage) {
        return new ZeroZDb(Objects.requireNonNull(storage, "storage"), null, false);
    }

    @SuppressWarnings("unchecked")
    public <T> T root() {
        return (T) storage.root();
    }

    /** The wrapped storage manager, for advanced/interop use. Do not bypass write-blocks with it. */
    public EmbeddedStorageManager storageManager() {
        return storage;
    }

    public <T> T read(Supplier<T> block) {
        checkOpen();
        lock.readLock().lock();
        try {
            return block.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void read(Runnable block) {
        read(() -> {
            block.run();
            return null;
        });
    }

    /**
     * Runs {@code block} exclusively (one writer at a time, readers excluded for the duration)
     * and flushes every enlisted object in one atomic, durable commit on normal exit. Nested
     * calls on the same thread join the outermost block and its single commit.
     */
    public <T> T writeResult(Function<WriteContext, T> block) {
        checkOpen();
        checkNotUpgradingFromRead();
        lock.writeLock().lock();
        WriteContextImpl outer = currentWrite.get();
        WriteContextImpl ctx = outer != null ? outer : new WriteContextImpl();
        if (outer == null) {
            currentWrite.set(ctx);
        }
        try {
            T result = block.apply(ctx);
            if (outer == null) {
                flush(ctx);
            }
            return result;
        } catch (RuntimeException | Error e) {
            if (outer == null) {
                ctx.rollback();
            }
            throw e;
        } finally {
            if (outer == null) {
                ctx.open = false;
                currentWrite.remove();
            }
            lock.writeLock().unlock();
        }
    }

    /**
     * Opens a write transaction held across method calls, for host frameworks with a
     * {@code begin()}/{@code commit()} API. See {@link WriteTransaction} — and prefer
     * {@link #write} in new code, because a block cannot be leaked.
     * <p>
     * Always use try-with-resources.
     */
    public WriteTransaction beginWrite() {
        checkOpen();
        checkNotUpgradingFromRead();
        lock.writeLock().lock();
        WriteContextImpl outer = currentWrite.get();
        if (outer != null) {
            return new WriteTransaction(this, outer, false);
        }
        WriteContextImpl ctx = new WriteContextImpl();
        currentWrite.set(ctx);
        return new WriteTransaction(this, ctx, true);
    }

    /** True when a write is in progress on this thread for this store. */
    public boolean isWriteActive() {
        return currentWrite.get() != null;
    }

    void finishCommit(WriteContextImpl ctx) {
        flush(ctx);
    }

    void endTransaction(WriteContextImpl ctx, boolean outermost) {
        if (outermost) {
            ctx.open = false;
            currentWrite.remove();
        }
        lock.writeLock().unlock();
    }

    public void write(Consumer<WriteContext> block) {
        writeResult(ctx -> {
            block.accept(ctx);
            return null;
        });
    }

    /**
     * Captures the object's current committed version — call when an edit begins (form-open),
     * then pass the value to {@link WriteContext#storeChecked} at save. Starts tracking the
     * object if it wasn't tracked yet.
     */
    public long baseline(Object object) {
        checkOpen();
        Objects.requireNonNull(object, "object");
        lock.readLock().lock();
        try {
            synchronized (versions) {
                return versions.computeIfAbsent(object, o -> 0L);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Registers a maintained index over a source Map or Collection reachable from the root
     * (pass a supplier so the source survives reopen). Scans once now, then stays correct at
     * every commit. See {@link Index}.
     */
    public <K, V> Index<K, V> index(String name, Class<V> type,
                                    java.util.function.Supplier<?> source,
                                    Function<V, K> key) {
        return registerIndex(new IndexImpl<>(this, name, type, source, key, false));
    }

    /** Like {@link #index} but with unique keys, enforced at commit. See {@link UniqueIndex}. */
    public <K, V> UniqueIndex<K, V> uniqueIndex(String name, Class<V> type,
                                                java.util.function.Supplier<?> source,
                                                Function<V, K> key) {
        return registerIndex(new IndexImpl<>(this, name, type, source, key, true)).asUnique();
    }

    private <K, V> IndexImpl<K, V> registerIndex(IndexImpl<K, V> index) {
        checkOpen();
        lock.writeLock().lock();
        try {
            index.rebuild();
            indexes.add(index);
            return index;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Commits applied to this store since it was opened. Replicas track this to detect change. */
    public long commitSequence() {
        return read(() -> commitSequence);
    }

    public void addCommitListener(CommitListener listener) {
        commitListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeCommitListener(CommitListener listener) {
        commitListeners.remove(listener);
    }

    private void flush(WriteContextImpl ctx) {
        if (ctx.dirty.isEmpty()) {
            return;
        }
        commitFlush(ctx, prepareFlush(ctx));
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("ZeroZDb is closed");
        }
    }

    /**
     * A thread holding only the read lock cannot take the write lock — {@link
     * ReentrantReadWriteLock} does not support upgrading, so the attempt would block forever
     * against itself. Fail loudly instead: a hang gives an operator nothing to work with, while
     * this names the mistake (starting a write inside a read block) and where it happened.
     * <p>
     * Holding the write lock already and then reading is fine — that is a downgrade, which the
     * lock does support, so reads nested inside a write are unaffected.
     */
    private void checkNotUpgradingFromRead() {
        if (lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException(
                    "Cannot start a write while this thread holds a read block on the same store: "
                            + "a read lock cannot be upgraded, so this would deadlock. Move the "
                            + "write outside the read block.");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            if (ownsManager) {
                storage.shutdown();
            }
        } finally {
            lock.writeLock().unlock();
            if (ownership != null) {
                ownership.release();
            }
        }
    }

    // ---- cross-store coordination hooks (package-private, used by CrossStoreWrite) ----

    /** Stable per-JVM key for total-order lock acquisition across stores. */
    String orderingKey() {
        return ownership != null
                ? ownership.directory().toAbsolutePath().toString()
                : "~attached:" + System.identityHashCode(this);
    }

    WriteContextImpl beginCross() {
        checkOpen();
        lock.writeLock().lock();
        if (currentWrite.get() != null) {
            lock.writeLock().unlock();
            throw new IllegalStateException(
                    "A cross-store write cannot start inside an active write-block on " + orderingKey());
        }
        WriteContextImpl ctx = new WriteContextImpl();
        currentWrite.set(ctx);
        return ctx;
    }

    void endCross(WriteContextImpl ctx) {
        ctx.open = false;
        currentWrite.remove();
        lock.writeLock().unlock();
    }

    /** Phase 1: version checks + index validation/planning. Throws before anything persists. */
    List<Runnable> prepareFlush(WriteContextImpl ctx) {
        synchronized (versions) {
            for (Map.Entry<Object, Long> check : ctx.versionChecks.entrySet()) {
                long current = versions.getOrDefault(check.getKey(), 0L);
                if (current != check.getValue()) {
                    throw new StaleObjectException(check.getKey(), check.getValue(), current);
                }
            }
        }
        List<Runnable> indexOps = new java.util.ArrayList<>();
        for (IndexImpl<?, ?> index : indexes) {
            index.prepare(ctx.dirty, ctx.snapshots, indexOps);
        }
        return indexOps;
    }

    /** Phase 2: the storage commit, then index ops, version bumps, listeners. */
    void commitFlush(WriteContextImpl ctx, List<Runnable> indexOps) {
        Storer storer = storage.createStorer();
        for (Object o : ctx.dirty) {
            storer.store(o);
        }
        storer.commit();
        for (Runnable op : indexOps) {
            op.run();
        }
        synchronized (versions) {
            for (Object o : ctx.dirty) {
                versions.computeIfPresent(o, (k, v) -> v + 1);
            }
        }
        long sequence = ++commitSequence;
        int count = ctx.dirty.size();
        for (CommitListener listener : commitListeners) {
            listener.afterCommit(sequence, count);
        }
    }
}
