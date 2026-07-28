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

import com.zeroz4j.db.Durability;
import com.zeroz4j.db.StoreOwnedException;
import com.zeroz4j.db.ZeroZDb;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Auto-server mode: point a JVM at a store directory and stop worrying about who else is
 * pointed at it.
 * <p>
 * The first JVM to open the store <em>owns</em> it — full embedded speed — and quietly serves
 * the same store on a socket, publishing its address beside the data. Any later JVM discovers
 * that address and becomes a client, its commands executed by the owner. If the owner dies, a
 * client detects the broken connection and either reconnects to a new owner or, if the store is
 * unowned, <strong>promotes itself</strong> to owner and carries on.
 * <p>
 * {@link DbCommand} and {@link DbQuery} run identically in either role, so application code
 * does not know or care which JVM it is running in. (Lambda-style {@code db.write(...)} remains
 * available on an owner via {@link #localDb()}, but only there — a lambda cannot be shipped to
 * a JVM that lacks its code.)
 */
public final class ZeroZDbNode implements AutoCloseable {

    /**
     * How a node participates in a store. The API is identical in every mode, so the same
     * application code runs against a private local store and a shared served one.
     */
    public enum Mode {
        /**
         * Own the store, no socket, no clients, no replicas — plain embedded EclipseStore with
         * transactions, indexes and constraints. Exactly one copy of the graph in memory, so
         * there is no replication overhead of any kind. The right mode for data only this JVM
         * touches (e.g. a per-tenant segment).
         */
        EMBEDDED,
        /**
         * Own the store if it is free and serve it to other JVMs; otherwise join the owner as a
         * client. Survives owner loss by reconnecting or promoting itself.
         */
        AUTO_SERVER,
        /**
         * Never own data: always connect to a separate owner, and fail if none exists. The
         * dedicated-server deployment shape.
         */
        CLIENT_ONLY
    }

    private final Path storeDir;
    private final Supplier<Object> rootSupplier;
    private final String storeName;
    private final String schemaId;
    private final Durability durability;
    private final Mode mode;
    private final boolean allowPromotion;
    private final Endpoint remote;
    private final String secret;
    private final javax.net.ssl.SSLContext sslContext;

    private final com.zeroz4j.db.lease.OwnershipArbiter arbiter;
    private final String ownerId;

    private ZeroZDb db;
    private ZeroZDbServer server;
    private ZeroZDbClient client;
    private com.zeroz4j.db.lease.OwnershipArbiter.Lease lease;
    private volatile boolean closed;

    private ZeroZDbNode(Builder builder) {
        this.storeDir = builder.storeDir;
        this.rootSupplier = builder.rootSupplier;
        this.storeName = builder.storeName;
        this.schemaId = builder.schemaId;
        this.durability = builder.durability;
        this.mode = builder.mode;
        this.arbiter = builder.arbiter;
        this.ownerId = builder.ownerId;
        this.remote = builder.remoteHost == null ? null
                : new Endpoint(builder.remoteHost, builder.remotePort, builder.schemaId, -1);
        this.secret = builder.secret;
        this.sslContext = builder.sslContext;
        this.allowPromotion = builder.mode != Mode.CLIENT_ONLY;
        if (mode == Mode.EMBEDDED) {
            this.db = ZeroZDb.open(rootSupplier.get(), storeDir, durability);
        } else {
            assumeRole(true);      // at startup, prefer owning an unowned store
        }
    }

    public static Builder builder(Path storeDir, Supplier<Object> rootSupplier) {
        return new Builder(storeDir, rootSupplier);
    }

    /** Auto-server mode with defaults: store name "main", schema "default". */
    public static ZeroZDbNode open(Path storeDir, Supplier<Object> rootSupplier) {
        return builder(storeDir, rootSupplier).build();
    }

    /**
     * A private local store: owned by this JVM, served to nobody, one copy of the graph in
     * memory. Same API as every other mode — see {@link Mode#EMBEDDED}.
     */
    public static ZeroZDbNode embedded(Path storeDir, Supplier<Object> rootSupplier) {
        return builder(storeDir, rootSupplier).mode(Mode.EMBEDDED).build();
    }

    /** True when this node holds the store's data locally (embedded, or owner of a served store). */
    public boolean isOwner() {
        return db != null;
    }

    /** True when this node also serves the store to other JVMs. */
    public boolean isServing() {
        return server != null;
    }

    public Mode mode() {
        return mode;
    }

    /**
     * The local engine — present only on an owner. Use for lambda write-blocks, index
     * registration and anything else that must run in-process; {@code null} on a client.
     */
    public ZeroZDb localDb() {
        return db;
    }

    /** The port this node serves on, or the port it is connected to. Not valid when EMBEDDED. */
    public int port() {
        if (mode == Mode.EMBEDDED) {
            throw new IllegalStateException("An EMBEDDED node has no port; it serves nobody");
        }
        return isServing() ? server.port() : endpointOrThrow().port();
    }

    /**
     * A locally-readable view of the store, whichever role this node holds: on an owner it is
     * the live graph; on a client it is a {@link ReplicaView} refreshed from the owner. Either
     * way {@code read} is heap access, not a round trip. Close it when done.
     * <p>
     * On a client the view is stale by at most one refresh (see {@link ReplicaView}); if a read
     * must be current, use {@link #query} instead, which always executes on the owner.
     */
    public <R> LocalReads<R> localReads() {
        checkOpen();
        if (isOwner()) {
            ZeroZDb owner = db;
            return new LocalReads<>(reader -> owner.read(() -> reader.apply(owner.root())),
                    () -> {
                    });
        }
        Endpoint endpoint = remote != null ? remote : endpointOrThrow();
        ReplicaView<R> replica = ReplicaView.of(
                ZeroZDbClient.connect(endpoint.host(), endpoint.port(), connectOptions()),
                storeName);
        return new LocalReads<>(replica::read, replica::close);
    }

    /** Heap-speed reads over whichever local graph this node has: live (owner) or replica. */
    public static final class LocalReads<R> implements AutoCloseable {
        private final java.util.function.Function<java.util.function.Function<R, Object>, Object> reader;
        private final Runnable closer;

        private LocalReads(java.util.function.Function<java.util.function.Function<R, Object>, Object> reader,
                           Runnable closer) {
            this.reader = reader;
            this.closer = closer;
        }

        @SuppressWarnings("unchecked")
        public <T> T read(java.util.function.Function<R, T> fn) {
            return (T) reader.apply((java.util.function.Function<R, Object>) fn);
        }

        @Override
        public void close() {
            closer.run();
        }
    }

    public <R> R execute(DbCommand<R> command) {
        return call(command, true);
    }

    public <R> R query(DbQuery<R> query) {
        return call(query, false);
    }

    @SuppressWarnings("unchecked")
    private <R> R call(Object payload, boolean write) {
        checkOpen();
        if (isOwner()) {
            if (write) {
                DbCommand<R> command = (DbCommand<R>) payload;
                return db.writeResult(ctx -> command.execute(ctx, db.root()));
            }
            DbQuery<R> query = (DbQuery<R>) payload;
            return db.read(() -> query.execute(db.root()));
        }
        try {
            return write
                    ? client.execute(storeName, (DbCommand<R>) payload)
                    : client.query(storeName, (DbQuery<R>) payload);
        } catch (UncheckedIOException e) {
            // The owner vanished mid-request. Re-establish a role, then retry once: the caller
            // sees a slow call rather than a failure, which is the whole point of the mode.
            recover();
            return call(payload, write);
        }
    }

    /** Re-runs role selection after losing the owner: reconnect to a new owner, or promote. */
    private synchronized void recover() {
        if (closed || isOwner()) {
            return;
        }
        closeClientQuietly();
        assumeRole(false);         // after losing an owner, prefer a newly-elected one
    }

    /**
     * Joins the store in whichever role is available. {@code ownershipFirst} decides the order
     * we try: at startup, owning an unowned store is the goal; after an owner vanishes, another
     * node may already have taken over, so reconnecting is tried first and ownership is the
     * fallback. Ownership is only ever attempted when this node is allowed to hold it.
     */
    private void assumeRole(boolean ownershipFirst) {
        long deadline = System.currentTimeMillis() + 30_000;
        RuntimeException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (ownershipFirst && allowPromotion) {
                try {
                    becomeOwner();
                    return;
                } catch (StoreOwnedException e) {
                    lastFailure = e;
                }
            }
            try {
                becomeClient();
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
            }
            if (!ownershipFirst && allowPromotion) {
                try {
                    becomeOwner();
                    return;
                } catch (StoreOwnedException e) {
                    lastFailure = e;
                }
            }
            if (!allowPromotion && Endpoint.read(storeDir) == null) {
                throw new IllegalStateException("Store " + storeDir
                        + " has no owner and this node is configured client-only", lastFailure);
            }
            sleep(100);
        }
        throw new IllegalStateException("Could not join store at " + storeDir
                + " as owner or client", lastFailure);
    }

    private void becomeOwner() {
        com.zeroz4j.db.lease.OwnershipArbiter.Lease acquired = arbiter.tryAcquire(storeDir, ownerId);
        if (acquired == null) {
            throw new StoreOwnedException(storeDir, "another process holds the ownership lease");
        }
        ZeroZDb opened;
        try {
            // The arbiter already holds the exclusive claim; taking the store's own lock as
            // well would collide with it inside this JVM.
            opened = ZeroZDb.openUnguarded(rootSupplier.get(), storeDir, durability);
        } catch (RuntimeException e) {
            acquired.close();
            throw e;
        }
        try {
            ZeroZDbServer started = ZeroZDbServer.builder()
                    .store(storeName, opened)
                    .schemaId(schemaId)
                    .start();
            Endpoint.write(storeDir, new Endpoint("127.0.0.1", started.port(), schemaId,
                    ProcessHandle.current().pid()));
            this.db = opened;
            this.server = started;
            this.lease = acquired;
            this.client = null;
            // Losing the lease means another process may already be taking over: stop serving
            // at once rather than risk two owners writing the same store.
            acquired.onLost(this::stepDown);
        } catch (RuntimeException e) {
            opened.close();
            acquired.close();
            throw e;
        }
    }

    /**
     * Relinquishes ownership because the lease was lost. Stops serving and closes the store; the
     * next call re-runs role selection and this node rejoins as a client of the new owner.
     */
    private synchronized void stepDown() {
        if (closed || server == null) {
            return;
        }
        Endpoint.delete(storeDir);
        server.close();
        server = null;
        db.close();
        db = null;
        lease = null;
    }

    private void becomeClient() {
        Endpoint endpoint = remote != null ? remote : Endpoint.read(storeDir);
        if (endpoint == null) {
            throw new IllegalStateException("No endpoint published for store " + storeDir
                    + " and no remote address configured");
        }
        this.client = ZeroZDbClient.connect(endpoint.host(), endpoint.port(),
                connectOptions().schemaId(schemaId));
        this.db = null;
        this.server = null;
    }

    private ConnectOptions connectOptions() {
        ConnectOptions options = ConnectOptions.create().schemaId(schemaId);
        if (secret != null) {
            options.secret(secret);
        }
        if (sslContext != null) {
            options.tls(sslContext);
        }
        return options;
    }

    private Endpoint endpointOrThrow() {
        Endpoint endpoint = Endpoint.read(storeDir);
        if (endpoint == null) {
            throw new IllegalStateException("No endpoint for store " + storeDir);
        }
        return endpoint;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Node is closed");
        }
    }

    private void closeClientQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
            }
            client = null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while joining store", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeClientQuietly();
        if (server != null) {
            Endpoint.delete(storeDir);
            server.close();
            server = null;
        }
        if (db != null) {
            db.close();
            db = null;
        }
        if (lease != null) {
            lease.close();
            lease = null;
        }
    }

    public static final class Builder {
        private final Path storeDir;
        private final Supplier<Object> rootSupplier;
        private String storeName = "main";
        private String schemaId = "default";
        private Durability durability = Durability.SYNC;
        private Mode mode = Mode.AUTO_SERVER;
        private com.zeroz4j.db.lease.OwnershipArbiter arbiter =
                new com.zeroz4j.db.lease.FileLockArbiter();
        private String ownerId = ProcessHandle.current().pid() + "@"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        private String remoteHost;
        private int remotePort;
        private String secret;
        private javax.net.ssl.SSLContext sslContext;

        private Builder(Path storeDir, Supplier<Object> rootSupplier) {
            this.storeDir = storeDir;
            this.rootSupplier = rootSupplier;
        }

        public Builder storeName(String storeName) {
            this.storeName = storeName;
            return this;
        }

        public Builder schemaId(String schemaId) {
            this.schemaId = schemaId;
            return this;
        }

        public Builder durability(Durability durability) {
            this.durability = durability;
            return this;
        }

        /** See {@link Mode}. Defaults to {@link Mode#AUTO_SERVER}. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * How ownership is decided. Defaults to
         * {@link com.zeroz4j.db.lease.FileLockArbiter} (local disks and RWO volumes); use
         * {@link com.zeroz4j.db.lease.LeaseFileArbiter} for shared volumes across hosts or
         * containers, where OS file locks cannot be trusted.
         */
        public Builder arbiter(com.zeroz4j.db.lease.OwnershipArbiter arbiter) {
            this.arbiter = arbiter;
            return this;
        }

        /**
         * Connects to a server at a fixed address instead of discovering one beside the store.
         * Required whenever the server is on another host, where there is no shared endpoint
         * file to read — implies {@link Mode#CLIENT_ONLY}.
         */
        public Builder remote(String host, int port) {
            this.remoteHost = host;
            this.remotePort = port;
            return mode(Mode.CLIENT_ONLY);
        }

        /** Shared secret presented to the server. */
        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        /** TLS context for the connection to the server. */
        public Builder tls(javax.net.ssl.SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        /** Identifies this process in the ownership record. Defaults to pid + random suffix. */
        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        /**
         * @deprecated use {@link #mode(Mode)}; {@code false} maps to {@link Mode#CLIENT_ONLY}.
         */
        @Deprecated
        public Builder allowPromotion(boolean allowPromotion) {
            return mode(allowPromotion ? Mode.AUTO_SERVER : Mode.CLIENT_ONLY);
        }

        public ZeroZDbNode build() {
            return new ZeroZDbNode(this);
        }
    }
}
