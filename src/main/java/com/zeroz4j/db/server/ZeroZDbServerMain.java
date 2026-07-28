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
package com.zeroz4j.db.server;

import com.zeroz4j.db.ZeroZDb;
import com.zeroz4j.db.net.Endpoint;
import com.zeroz4j.db.net.ZeroZDbServer;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The standalone ZeroZ DB server: a headless daemon that owns a set of stores and serves them.
 *
 * <pre>
 * java -cp zeroz4j-db.jar:my-domain.jar com.zeroz4j.db.server.ZeroZDbServerMain server.properties
 * </pre>
 *
 * Your domain jar must be on the classpath: the server executes your {@code DbCommand}s and
 * maintains your indexes, so it is versioned in lock-step with your model (see the schema
 * handshake). On SIGTERM/Ctrl-C it stops accepting, withdraws each store's endpoint file and
 * closes every store cleanly — an acknowledged write is already durable, so nothing is lost.
 */
public final class ZeroZDbServerMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("""
                    Usage: ZeroZDbServerMain <config.properties>

                      port        = 5150
                      schemaId    = myapp-v3
                      durability  = SYNC | OS_BUFFERED
                      store.<name>.dir  = /path/to/store
                      store.<name>.root = com.example.RootClass""");
            System.exit(2);
        }

        ServerConfig config = ServerConfig.load(Path.of(args[0]));
        Instant started = Instant.now();
        Map<String, ZeroZDb> opened = new LinkedHashMap<>();
        List<Path> endpointDirs = new ArrayList<>();

        log("starting, schemaId=" + config.schemaId() + " durability=" + config.durability());
        for (ServerConfig.StoreConfig store : config.stores()) {
            Instant openStart = Instant.now();
            ZeroZDb db = ZeroZDb.open(store.newRoot(), store.directory(), config.durability());
            opened.put(store.name(), db);
            log("store '" + store.name() + "' opened from " + store.directory()
                    + " in " + Duration.between(openStart, Instant.now()).toMillis() + " ms");
        }

        ZeroZDbServer.Builder builder = ZeroZDbServer.builder()
                .schemaId(config.schemaId())
                .port(config.port())
                .bindAddress(config.bindAddress());
        if (config.secret() != null) {
            builder.secret(config.secret());
        }
        opened.forEach(builder::store);
        ZeroZDbServer server = builder.start();

        com.zeroz4j.db.console.ConsoleServer console = null;
        if (config.consolePort() > 0) {
            com.zeroz4j.db.console.ConsoleServer.Builder consoleBuilder =
                    com.zeroz4j.db.console.ConsoleServer.builder().port(config.consolePort());
            opened.forEach(consoleBuilder::store);
            if (config.consolePassword() != null) {
                consoleBuilder.password(config.consolePassword());
            }
            console = consoleBuilder.start();
            log("console on " + console.url());
        }
        com.zeroz4j.db.console.ConsoleServer consoleRef = console;

        // Publish an endpoint beside each store so auto-server clients can discover this daemon.
        for (ServerConfig.StoreConfig store : config.stores()) {
            Endpoint.write(store.directory(), new Endpoint("127.0.0.1", server.port(),
                    config.schemaId(), ProcessHandle.current().pid()));
            endpointDirs.add(store.directory());
        }

        log("listening on port " + server.port() + ", " + opened.size() + " store(s), ready in "
                + Duration.between(started, Instant.now()).toMillis() + " ms");

        CountDownLatch shutdown = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean stopping =
                new java.util.concurrent.atomic.AtomicBoolean();
        Runnable stop = () -> {
            if (!stopping.compareAndSet(false, true)) {
                return;
            }
            log("shutting down (served " + server.requestsServed() + " requests)");
            endpointDirs.forEach(Endpoint::delete);
            if (consoleRef != null) {
                consoleRef.close();
            }
            server.close();
            opened.values().forEach(ZeroZDb::close);
            log("stopped");
            shutdown.countDown();
        };

        // SIGTERM (docker stop, kubectl delete, systemctl stop) runs the hook on Unix.
        Runtime.getRuntime().addShutdownHook(new Thread(stop, "zerozdb-shutdown"));

        // Windows has no SIGTERM: Process.destroy() is a hard kill and hooks never run. Closing
        // stdin is therefore also a shutdown signal, which works on every platform and suits
        // supervisors that manage the daemon through pipes.
        Thread.ofPlatform().daemon().name("zerozdb-stdin-watch").start(() -> {
            try {
                while (System.in.read() != -1) {
                    // ignore input; only EOF matters
                }
            } catch (Exception ignored) {
            }
            stop.run();
        });

        shutdown.await();
        System.exit(0);
    }

    private static void log(String message) {
        System.out.println(Instant.now() + " [zerozdb] " + message);
        System.out.flush();
    }
}
