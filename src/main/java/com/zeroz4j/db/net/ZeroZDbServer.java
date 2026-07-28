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

import org.eclipse.serializer.Serializer;
import org.eclipse.serializer.TypedSerializer;
import com.zeroz4j.db.ZeroZDb;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The ZeroZ DB storage server: owns its stores exclusively and executes clients' commands and
 * queries against them over a socket.
 * <p>
 * This is the "point another JVM at it and don't worry" rung. The server holds the only live
 * object graph; clients never touch the files. Each connection is handled on its own virtual
 * thread, and every write still funnels through the engine's single-writer lock, so remote
 * concurrency has exactly the same guarantees as embedded concurrency.
 * <p>
 * The server loads the domain classes (it executes commands and runs index extractors), so it
 * is versioned in lock-step with the model jar — see {@link #schemaId}.
 */
public final class ZeroZDbServer implements AutoCloseable {

    private final Map<String, ZeroZDb> stores;
    private final String schemaId;
    private final String secret;
    private final String bindAddress;
    private final Map<String, StoreSchema> schemas;
    private final SchemaPolicy schemaPolicy;
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final CopyOnWriteArrayList<Socket> connections = new CopyOnWriteArrayList<>();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean running = true;

    /**
     * Per-store commit signal. The engine calls back on every commit (while holding the write
     * lock, so ordering is exact); waiters parked in {@link AwaitCommit} wake immediately.
     */
    private static final class CommitSignal {
        private long sequence;

        synchronized void advance(long newSequence) {
            sequence = newSequence;
            notifyAll();
        }

        synchronized long awaitAfter(long afterSequence, long timeoutMillis)
                throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (sequence <= afterSequence) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                wait(remaining);
            }
            return sequence;
        }
    }

    private final Map<String, CommitSignal> signals = new java.util.HashMap<>();

    private ZeroZDbServer(Builder builder) {
        Map<String, ZeroZDb> stores = builder.stores;
        String schemaId = builder.schemaId;
        int port = builder.port;
        this.secret = builder.secret;
        this.bindAddress = builder.bindAddress;
        this.schemas = Map.copyOf(builder.schemas);
        this.schemaPolicy = builder.schemaPolicy;
        this.stores = Map.copyOf(stores);
        this.schemaId = Objects.requireNonNull(schemaId, "schemaId");
        this.stores.forEach((name, db) -> {
            CommitSignal signal = new CommitSignal();
            signal.advance(db.commitSequence());
            signals.put(name, signal);
            db.addCommitListener((sequence, count) -> signal.advance(sequence));
        });
        try {
            this.serverSocket = builder.sslContext != null
                    ? builder.sslContext.getServerSocketFactory().createServerSocket()
                    : new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(bindAddress, port));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot bind ZeroZ DB server to "
                    + bindAddress + ":" + port, e);
        }
        this.acceptThread = Thread.ofPlatform().name("zerozdb-accept").daemon().start(this::acceptLoop);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public long requestsServed() {
        return served.get();
    }

    public long connectionsRejected() {
        return rejected.get();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                connections.add(socket);
                Thread.ofVirtual().name("zerozdb-conn-" + socket.getPort())
                        .start(() -> serve(socket));
            } catch (IOException e) {
                if (running) {
                    // transient accept failure: keep serving
                    continue;
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        Serializer<byte[]> serializer = TypedSerializer.Bytes();
        try (socket;
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            Handshake clientHello = serializer.deserialize(Frames.read(in));
            Handshake reply = new Handshake(schemaId);
            reply.stores = stores.keySet().toArray(String[]::new);
            Set<String> admittedForThisConnection = Set.of();

            boolean authenticated = secret == null || constantTimeEquals(secret, clientHello.secret);
            if (!authenticated) {
                reply.accepted = false;
                reply.rejectionReason = "authentication failed";
                reply.stores = new String[0];      // disclose nothing to an unauthenticated caller
                rejected.incrementAndGet();
            } else {
                // Admittance is per store, so a client whose model matches some stores but not
                // others keeps working with the ones it can use — the blue/green case.
                Map<String, String> refused = new LinkedHashMap<>();
                List<String> admitted = new ArrayList<>();
                for (String store : stores.keySet()) {
                    String reason = admittanceRefusal(store, clientHello);
                    if (reason == null) {
                        admitted.add(store);
                    } else {
                        refused.put(store, reason);
                    }
                }
                reply.stores = admitted.toArray(String[]::new);
                reply.refusedStores = refused;
                reply.accepted = !admitted.isEmpty();
                if (!reply.accepted) {
                    reply.rejectionReason = "no store accepts this client's schema: " + refused;
                    rejected.incrementAndGet();
                }
                admittedForThisConnection = Set.copyOf(admitted);
            }
            Frames.write(out, serializer.serialize(reply));
            if (!reply.accepted) {
                return;
            }

            byte[] frame;
            while (running && (frame = Frames.readOrNull(in)) != null) {
                Request request = serializer.deserialize(frame);
                Response response = handle(request, admittedForThisConnection);
                Frames.write(out, serializer.serialize(response));
                served.incrementAndGet();
            }
        } catch (IOException e) {
            // client vanished or shutdown; nothing to recover
        } finally {
            connections.remove(socket);
            try {
                serializer.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Decides whether a client may use a store. Returns the refusal reason, or {@code null} to
     * admit. Per store, because a client may legitimately match some models and not others.
     */
    private String admittanceRefusal(String store, Handshake hello) {
        StoreSchema schema = schemas.get(store);
        String storeSchemaId = schema != null && schema.schemaId() != null
                ? schema.schemaId() : schemaId;
        String clientSchemaId = hello.storeSchemaIds != null
                && hello.storeSchemaIds.containsKey(store)
                ? hello.storeSchemaIds.get(store) : hello.schemaId;

        if (storeSchemaId.equals(clientSchemaId)) {
            return null;
        }
        if (schemaPolicy == SchemaPolicy.EXACT) {
            return "schema '" + clientSchemaId + "' != '" + storeSchemaId + "'";
        }
        if (schema == null || schema.descriptor() == null || hello.descriptorText == null) {
            return "schema '" + clientSchemaId + "' != '" + storeSchemaId
                    + "' and no descriptors available to compare";
        }
        // The client must be able to READ what this store holds: additions are invisible to an
        // older client and defaulted for a newer one, so only additions are tolerable. Writes
        // are safe regardless, being commands executed here with this server's classes.
        com.zeroz4j.db.schema.SchemaCompatibility.Report report =
                com.zeroz4j.db.schema.SchemaCompatibility.compare(
                        schema.descriptor(),
                        com.zeroz4j.db.schema.SchemaDescriptor.parse(hello.descriptorText));
        if (report.isRollbackCompatible()) {
            return null;
        }
        return "incompatible model: " + report.problems();
    }

    private Response handle(Request request, Set<String> admitted) {
        try {
            ZeroZDb db = stores.get(request.store);
            if (db == null) {
                throw new IllegalArgumentException("Unknown store: " + request.store);
            }
            if (!admitted.contains(request.store)) {
                throw new IllegalArgumentException("Store '" + request.store
                        + "' did not accept this client's schema");
            }
            if (request.payload instanceof AwaitCommit await) {
                // Long poll: park until this store commits again (or the client's timeout).
                // Never holds a store lock, so waiters cost nothing but a parked thread.
                return Response.ok(request.id, signals.get(request.store)
                        .awaitAfter(await.afterSequence, await.timeoutMillis));
            }
            if (request.write) {
                @SuppressWarnings("unchecked")
                DbCommand<Object> command = (DbCommand<Object>) request.payload;
                Object result = db.writeResult(ctx -> command.execute(ctx, db.root()));
                return Response.ok(request.id, result);
            }
            @SuppressWarnings("unchecked")
            DbQuery<Object> query = (DbQuery<Object>) request.payload;
            Object result = db.read(() -> query.execute(db.root()));
            return Response.ok(request.id, result);
        } catch (Throwable t) {
            return Response.failure(request.id, t);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : connections) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        try {
            acceptThread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Length-independent comparison, so timing cannot reveal the secret. */
    private static boolean constantTimeEquals(String expected, String presented) {
        if (presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static final class Builder {
        private final Map<String, ZeroZDb> stores = new LinkedHashMap<>();
        private String schemaId = "default";
        private int port;
        private String secret;
        private String bindAddress = "127.0.0.1";
        private javax.net.ssl.SSLContext sslContext;
        private final Map<String, StoreSchema> schemas = new LinkedHashMap<>();
        private SchemaPolicy schemaPolicy = SchemaPolicy.EXACT;

        /** Registers a store under a name clients address it by. */
        public Builder store(String name, ZeroZDb db) {
            stores.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(db, "db"));
            return this;
        }

        /**
         * Registers a store together with its own model version, so one server can serve stores
         * at different versions — the reason an app release touching one model does not lock
         * clients out of the others.
         */
        public Builder store(String name, ZeroZDb db, StoreSchema schema) {
            store(name, db);
            schemas.put(name, schema);
            return this;
        }

        /**
         * Default model version for stores that do not declare their own. Clients presenting a
         * different id are refused unless {@link #schemaPolicy} allows compatible models.
         */
        public Builder schemaId(String schemaId) {
            this.schemaId = schemaId;
            return this;
        }

        /**
         * Whether a differing client model may still be admitted when it is only additively
         * different. Defaults to {@link SchemaPolicy#EXACT}; use
         * {@link SchemaPolicy#ADDITIVE_COMPATIBLE} for rolling blue/green releases.
         */
        public Builder schemaPolicy(SchemaPolicy schemaPolicy) {
            this.schemaPolicy = schemaPolicy;
            return this;
        }

        /** 0 (default) binds an ephemeral port; read it back with {@link #port()}. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Interface to listen on. Defaults to {@code 127.0.0.1} — loopback only, so a store is
         * never exposed by accident. Set {@code 0.0.0.0} (or a specific address) to accept
         * connections from other hosts, and <strong>only</strong> together with
         * {@link #secret(String)} and {@link #tls}: a reachable server with neither lets anyone
         * who can open a socket run commands against your data.
         */
        public Builder bindAddress(String bindAddress) {
            this.bindAddress = bindAddress;
            return this;
        }

        /**
         * Requires clients to present this shared secret at connect. Compared in constant time,
         * and rejections do not reveal whether the secret or the schema was wrong.
         * <p>
         * Over a non-loopback interface, use with {@link #tls} — otherwise the secret crosses
         * the network in the clear.
         */
        public Builder secret(String secret) {
            this.secret = secret;
            return this;
        }

        /** Serves TLS using this context. See {@link Tls} for building one from a keystore. */
        public Builder tls(javax.net.ssl.SSLContext sslContext) {
            this.sslContext = sslContext;
            return this;
        }

        public ZeroZDbServer start() {
            if (stores.isEmpty()) {
                throw new IllegalStateException("At least one store must be registered");
            }
            if (!"127.0.0.1".equals(bindAddress) && "localhost".equals(bindAddress) == false
                    && secret == null) {
                throw new IllegalStateException("Refusing to listen on " + bindAddress
                        + " without a secret: a reachable ZeroZ DB server with no authentication "
                        + "lets anyone who can reach the port read and write your data. Call "
                        + "secret(...) (and tls(...)), or bind to 127.0.0.1.");
            }
            return new ZeroZDbServer(this);
        }
    }
}
