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
import com.zeroz4j.db.StaleObjectException;
import com.zeroz4j.db.UniqueConstraintException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client handle on a remote {@link ZeroZDbServer}: send a {@link DbCommand} to write or a
 * {@link DbQuery} to read, both executed on the server against the live graph.
 * <p>
 * One connection per client, guarded so concurrent callers serialize on the wire — cheap
 * because the server's writes serialize anyway. For parallel throughput, create several
 * clients (that is what the multi-JVM stress harness does).
 * <p>
 * Engine failures cross the wire faithfully: a unique-constraint violation or stale edit
 * arrives as the same exception type it would be in embedded mode, so retry logic is portable.
 */
public final class ZeroZDbClient implements AutoCloseable {

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Serializer<byte[]> serializer;
    private final Set<String> remoteStores;
    private final Map<String, String> refusedStores;
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final Object wireLock = new Object();

    private ZeroZDbClient(String host, int port, ConnectOptions options) {
        String schemaId = options.schemaId;
        int timeoutMillis = options.timeoutMillis;
        String secret = options.secret;
        javax.net.ssl.SSLContext sslContext = options.sslContext;
        try {
            if (sslContext != null) {
                javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket)
                        sslContext.getSocketFactory().createSocket();
                sslSocket.connect(new InetSocketAddress(host, port), timeoutMillis);
                sslSocket.startHandshake();
                this.socket = sslSocket;
            } else {
                this.socket = new Socket();
                this.socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            }
            this.socket.setTcpNoDelay(true);
            this.in = new DataInputStream(socket.getInputStream());
            this.out = new DataOutputStream(socket.getOutputStream());
            this.serializer = TypedSerializer.Bytes();

            Handshake hello = new Handshake(schemaId, secret);
            if (!options.storeSchemaIds.isEmpty()) {
                hello.storeSchemaIds = Map.copyOf(options.storeSchemaIds);
            }
            if (options.descriptor != null) {
                hello.descriptorText = options.descriptor.toText();
            }
            Frames.write(out, serializer.serialize(hello));
            Handshake reply = serializer.deserialize(Frames.read(in));
            if (!reply.accepted) {
                close();
                if (reply.rejectionReason != null
                        && reply.rejectionReason.contains("authentication")) {
                    throw new AuthenticationException(reply.rejectionReason);
                }
                throw new SchemaMismatchException(schemaId, reply.schemaId);
            }
            this.remoteStores = Set.of(reply.stores);
            this.refusedStores = reply.refusedStores == null
                    ? Map.of() : Map.copyOf(reply.refusedStores);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot connect to ZeroZ DB server at "
                    + host + ":" + port, e);
        }
    }

    public static ZeroZDbClient connect(String host, int port, String schemaId) {
        return connect(host, port, ConnectOptions.create().schemaId(schemaId));
    }

    /** Connects presenting a shared secret (see {@code ZeroZDbServer.Builder.secret}). */
    public static ZeroZDbClient connect(String host, int port, String schemaId, String secret) {
        return connect(host, port, ConnectOptions.create().schemaId(schemaId).secret(secret));
    }

    /** Connects over TLS, presenting a shared secret. Use this for anything off-host. */
    public static ZeroZDbClient connect(String host, int port, String schemaId, String secret,
                                        javax.net.ssl.SSLContext sslContext) {
        return connect(host, port, ConnectOptions.create()
                .schemaId(schemaId).secret(secret).tls(sslContext));
    }

    /** Full control: per-store schema ids, a model descriptor, credentials, TLS. */
    public static ZeroZDbClient connect(String host, int port, ConnectOptions options) {
        return new ZeroZDbClient(host, port, options);
    }

    /** Stores this client may use — those whose model it is compatible with. */
    public Set<String> stores() {
        return remoteStores;
    }

    /**
     * Stores the server declined to expose to this client, with the reason. Non-empty during a
     * rolling release when one model has moved ahead of this build; log it, don't ignore it.
     */
    public Map<String, String> refusedStores() {
        return refusedStores;
    }

    /** Runs the command on the server inside one atomic durable write-block. */
    public <R> R execute(String store, DbCommand<R> command) {
        return call(store, command, true);
    }

    /** Runs the query on the server inside a read-block. */
    public <R> R query(String store, DbQuery<R> query) {
        return call(store, query, false);
    }

    /**
     * Blocks until the store's commit sequence exceeds {@code afterSequence} or the timeout
     * expires, returning the current sequence. Used by {@link ReplicaView}; note it occupies
     * this client's connection for the duration, so give a replica its own client.
     */
    public long awaitCommit(String store, long afterSequence, long timeoutMillis) {
        return call(store, new AwaitCommit(afterSequence, timeoutMillis), false);
    }

    @SuppressWarnings("unchecked")
    private <R> R call(String store, Object payload, boolean write) {
        long id = nextRequestId.getAndIncrement();
        Response response;
        synchronized (wireLock) {
            try {
                Frames.write(out, serializer.serialize(new Request(id, store, payload, write)));
                response = serializer.deserialize(Frames.read(in));
            } catch (IOException e) {
                throw new UncheckedIOException("ZeroZ DB request failed on the wire", e);
            }
        }
        if (response.failed()) {
            throw translate(response);
        }
        return (R) response.result;
    }

    private static RuntimeException translate(Response response) {
        String message = response.failureMessage == null ? "" : response.failureMessage;
        return switch (response.failureType) {
            case "com.zeroz4j.db.StaleObjectException" -> new StaleObjectException(message);
            case "com.zeroz4j.db.UniqueConstraintException" -> new UniqueConstraintException(message);
            case "java.lang.IllegalArgumentException" -> new IllegalArgumentException(message);
            case "java.lang.IllegalStateException" -> new IllegalStateException(message);
            default -> new RemoteFailureException(response.failureType, message);
        };
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (serializer != null) {
            try {
                serializer.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Convenience for tests and tools: connect several clients to the same server. */
    public static List<ZeroZDbClient> connectMany(String host, int port, String schemaId, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> connect(host, port, schemaId))
                .toList();
    }
}
