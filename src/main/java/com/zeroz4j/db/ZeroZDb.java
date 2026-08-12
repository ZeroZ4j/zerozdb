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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    private static final Logger LOG = System.getLogger(ZeroZDb.class.getName());

    /**
     * How long {@link #close()} waits for an in-flight write before giving up and naming the
     * thread that holds the block. Bounded deliberately: an unbounded wait against a leaked
     * write-block is a store that can never be closed, and therefore never released, with nothing
     * in the log to say why.
     * <p>
     * Thirty seconds, or {@code -Dzerozdb.closeTimeoutSeconds=N} — the knob exists for a consumer
     * whose write-blocks legitimately run longer than that, so raising it needs no recompile.
     */
    public static final Duration DEFAULT_CLOSE_TIMEOUT =
            Duration.ofSeconds(Long.getLong("zerozdb.closeTimeoutSeconds", 30L));

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
    private final OwnerAwareLock lock = new OwnerAwareLock();
    private final ThreadLocal<WriteContextImpl> currentWrite = new ThreadLocal<>();
    /**
     * Who holds the write lock right now, for any thread to read — {@link #currentWrite} is a
     * ThreadLocal and can only ever answer for its own thread, which is the one thread that
     * already knows. Written under the write lock, read without it: a diagnostic wants the most
     * recent answer, not a synchronised one.
     */
    private volatile WriteHold hold;
    private final List<CommitListener> commitListeners = new CopyOnWriteArrayList<>();
    private final List<IndexImpl<?, ?>> indexes = new CopyOnWriteArrayList<>();

    /**
     * Version side table: objects enter it on first {@link #baseline}, and only tracked objects
     * pay the bump cost at commit — untracked objects (the vast majority) cost nothing.
     * Guarded by its own monitor so baselines can be taken under the shared read lock.
     */
    private final IdentityHashMap<Object, Long> versions = new IdentityHashMap<>();
    private final String orderingKey;
    private long commitSequence;
    private volatile boolean closed;

    private ZeroZDb(EmbeddedStorageManager storage, StoreOwnership ownership, boolean ownsManager) {
        this.storage = storage;
        this.ownership = ownership;
        this.ownsManager = ownsManager;
        // Computed once: it identifies the store in every diagnostic and orders every cross-store
        // lock acquisition, and resolving a Path to an absolute string is not free.
        this.orderingKey = ownership != null
                ? ownership.directory().toAbsolutePath().toString()
                : "~attached:" + System.identityHashCode(this);
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
        WriteEntry entry = enterWrite(WriteHold.BLOCK);
        WriteContextImpl ctx = entry.context();
        boolean outermost = entry.outermost();
        try {
            T result = block.apply(ctx);
            if (outermost) {
                flush(ctx);
            }
            return result;
        } catch (RuntimeException | Error e) {
            if (outermost) {
                ctx.rollback();
            }
            throw e;
        } finally {
            if (outermost) {
                ctx.open = false;
                currentWrite.remove();
            }
            releaseWrite();
        }
    }

    /**
     * Takes the write lock and enters (or joins) a write context as one step. The set-up used to
     * sit between {@code lock()} and the {@code try} that releases it, which is the classic shape
     * of a permanently held lock — and a permanently held write lock on a store is a store that
     * can never be written to or closed again.
     */
    private WriteEntry enterWrite(String kind) {
        lock.writeLock().lock();
        try {
            WriteContextImpl outer = currentWrite.get();
            if (outer != null) {
                return new WriteEntry(outer, false, null);
            }
            WriteEntry entry = new WriteEntry(new WriteContextImpl(), true, noteHold(kind));
            currentWrite.set(entry.context());
            return entry;
        } catch (RuntimeException | Error e) {
            abandonEntry();
            throw e;
        }
    }

    /** {@code origin} is null for a block that joined an outer one, which owns the hold. */
    private record WriteEntry(WriteContextImpl context, boolean outermost, WriteHold origin) {
    }

    /** Unwinds a half-finished {@link #enterWrite}; the hold-count test skips a joined block. */
    private void abandonEntry() {
        if (lock.getWriteHoldCount() == 1) {
            currentWrite.remove();
            hold = null;
        }
        lock.writeLock().unlock();
    }

    /** Always release through here: the recorded holder must be dropped before the lock is. */
    private void releaseWrite() {
        if (lock.getWriteHoldCount() == 1) {
            hold = null;
        }
        lock.writeLock().unlock();
    }

    /**
     * Records the holder on the outermost acquisition only. A nested block joins the outer one, so
     * overwriting the hold would replace the origin that matters with one that does not.
     */
    private WriteHold noteHold(String kind) {
        if (lock.getWriteHoldCount() != 1) {
            return null;
        }
        WriteHold current = new WriteHold(kind);
        hold = current;
        return current;
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
        WriteEntry entry = enterWrite(WriteHold.TRANSACTION);
        try {
            WriteHold origin = entry.origin() != null
                    ? entry.origin()
                    : new WriteHold(WriteHold.TRANSACTION);
            return new WriteTransaction(this, entry.context(), entry.outermost(), origin);
        } catch (RuntimeException | Error e) {
            endTransaction(entry.context(), entry.outermost());
            throw e;
        }
    }

    /**
     * True when a write is in progress <em>on the calling thread</em>. This reads a ThreadLocal,
     * so it answers "am I inside a write?" and never "is anyone?" — for that, which is the
     * question an operator and {@link #close()} actually have, use {@link #hasOpenWriteBlock()}.
     */
    public boolean isWriteActive() {
        return currentWrite.get() != null;
    }

    /**
     * True when a write-block is open on this store, by <em>any</em> thread. Assert this at the
     * end of a request when the host uses {@link #beginWrite()}: it catches a leaked transaction
     * at the point it leaked, instead of days later when a close will not complete.
     */
    public boolean hasOpenWriteBlock() {
        return hold != null;
    }

    /**
     * The thread holding this store's write lock, or null when nothing holds it. Handing back the
     * thread rather than its name is deliberate — a host can dump its stack and see exactly what
     * that thread is doing instead of what it should be doing.
     */
    public Thread writeBlockOwner() {
        WriteHold current = hold;
        return current != null ? current.thread() : lock.getOwner();
    }

    /**
     * One sentence about what holds this store's lock, for a log line or an assertion message.
     * Never null, safe from any thread at any time, and correct the instant it was taken — which
     * is all a diagnostic needs.
     */
    public String describeLockState() {
        WriteHold current = hold;
        if (current != null) {
            return current.describe() + " and is still open. " + current.origin();
        }
        Thread owner = lock.getOwner();
        if (owner != null) {
            return "the write lock is held by thread \"" + owner.getName() + "\"";
        }
        int readers = lock.getReadLockCount();
        if (readers > 0) {
            return readers == 1 ? "one read block is open" : readers + " read blocks are open";
        }
        return "nothing holds the lock";
    }

    /**
     * Turns on capture of the stack that opened each write-block, JVM-wide, at roughly a
     * microsecond per block; also settable with {@code -Dzerozdb.traceWriteBlockOrigins=true}.
     * Off by default. Worth turning on while hunting a leak, because it turns "some thread left a
     * write-block open" into the line that left it open.
     */
    public static void traceWriteBlockOrigins(boolean enabled) {
        WriteHold.traceOrigins(enabled);
    }

    /** Whether write-block origin stacks are being captured. See {@link #traceWriteBlockOrigins}. */
    public static boolean isTracingWriteBlockOrigins() {
        return WriteHold.isTracingOrigins();
    }

    void finishCommit(WriteContextImpl ctx) {
        flush(ctx);
    }

    void endTransaction(WriteContextImpl ctx, boolean outermost) {
        if (outermost) {
            ctx.open = false;
            currentWrite.remove();
        }
        releaseWrite();
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
            noteHold(WriteHold.INDEX);
            index.rebuild();
            indexes.add(index);
            return index;
        } finally {
            releaseWrite();
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

    /**
     * Closes the store, waiting up to {@link #DEFAULT_CLOSE_TIMEOUT} for a write that is still in
     * flight. See {@link #close(Duration)} — including why this is bounded at all.
     *
     * @throws StoreBusyException if the write lock is still held when the wait expires
     */
    @Override
    public void close() {
        close(DEFAULT_CLOSE_TIMEOUT);
    }

    /**
     * Closes the store, waiting up to {@code timeout} for a write that is still in flight, then
     * naming whoever holds it rather than waiting for ever.
     * <p>
     * The unbounded version of this method could park for the life of the process: a leaked
     * write-block — a {@link #beginWrite()} whose transaction was never committed, rolled back or
     * closed — holds the write lock until its thread releases it, which a thread that has
     * forgotten the transaction exists never will. It reported nothing while doing so, and this
     * engine already refuses a read-to-write upgrade for the same reason: a hang gives an operator
     * nothing to work with, while a message names the mistake and where it happened.
     * <p>
     * On expiry the store stays open. It is not safe to shut the storage manager down underneath
     * a writer that may yet wake up, and pretending otherwise would trade a diagnosable hang for
     * an undiagnosable corruption. Fix the leak, or pass a longer timeout if the write is genuine.
     *
     * @throws StoreBusyException    if the write lock is still held when the wait expires
     * @throws IllegalStateException if called from inside a write-block on this thread
     */
    public void close(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (closed) {
            return;
        }
        if (currentWrite.get() != null) {
            // The lock is reentrant, so this would otherwise succeed: shut the storage down and
            // leave the enclosing block to commit into a dead manager.
            throw new IllegalStateException(
                    "Cannot close the store from inside a write block on this thread: the block's "
                            + "commit would run against a shut-down storage manager. Close it "
                            + "after the block ends.");
        }
        boolean acquired;
        try {
            acquired = lock.writeLock().tryLock(timeoutNanos(timeout), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to close store "
                    + orderingKey(), e);
        }
        if (!acquired) {
            String message = "Cannot close store " + orderingKey() + ": gave up after "
                    + describe(timeout) + " waiting for the write lock. The store cannot be closed "
                    + "until the open block commits, rolls back or is closed; if it was leaked (a "
                    + "beginWrite() whose transaction never ended) that is the bug to fix. "
                    + "Holding it: " + describeLockState();
            LOG.log(Level.ERROR, message);
            throw new StoreBusyException(writeBlockOwner(), message);
        }
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

    /** Saturating, so "wait essentially for ever" is a long wait rather than an overflow. */
    private static long timeoutNanos(Duration timeout) {
        if (timeout.isNegative()) {
            return 0L;
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String describe(Duration timeout) {
        long millis = timeout.toMillis();
        return millis >= 1000L ? (millis / 1000.0) + " s" : millis + " ms";
    }

    // ---- cross-store coordination hooks (package-private, used by CrossStoreWrite) ----

    /** Stable per-JVM key for total-order lock acquisition across stores, and the name in a log. */
    String orderingKey() {
        return orderingKey;
    }

    WriteContextImpl beginCross() {
        checkOpen();
        lock.writeLock().lock();
        try {
            if (currentWrite.get() != null) {
                throw new IllegalStateException(
                        "A cross-store write cannot start inside an active write-block on "
                                + orderingKey());
            }
            WriteContextImpl ctx = new WriteContextImpl();
            noteHold(WriteHold.CROSS_STORE);
            currentWrite.set(ctx);
            return ctx;
        } catch (RuntimeException | Error e) {
            // Including the guard above: this store never made it into the participant map, so
            // CrossStoreWrite's finally will not call endCross for it.
            abandonEntry();
            throw e;
        }
    }

    void endCross(WriteContextImpl ctx) {
        ctx.open = false;
        currentWrite.remove();
        releaseWrite();
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

    /**
     * {@link ReentrantReadWriteLock#getOwner()} is protected, and the identity of the thread
     * holding the write lock is the one thing worth knowing when a close will not complete. Two
     * lines to publish it, and it stays correct even for a hold nothing else recorded.
     */
    private static final class OwnerAwareLock extends ReentrantReadWriteLock {

        private static final long serialVersionUID = 1L;

        private OwnerAwareLock() {
            super(true);
        }

        @Override
        public Thread getOwner() {
            return super.getOwner();
        }
    }
}
