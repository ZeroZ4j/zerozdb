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
package com.zeroz4j.db.stress;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates a genuine multi-JVM run: one server JVM owning the store, N client JVMs
 * connecting over TCP, all hammering the same graph. Verifies invariants that span processes.
 * <p>
 * Runnable standalone:
 * {@code java -cp <test-cp> com.zeroz4j.db.stress.MultiJvmStress [clientJvms] [threadsPerJvm] [seconds] [SYNC|OS_BUFFERED]}
 */
public final class MultiJvmStress {

    private static final int ACCOUNTS = 200;
    private static final long OPENING_BALANCE = 100_000;

    public record ClientOutcome(long writes, long reads, long rejections, long violations,
                                long failures, long increments, String problem, int exitCode) {
    }

    public record Outcome(int clientJvms, long totalWrites, long totalReads, long totalRejections,
                          long totalViolations, long totalFailures, long clientIncrements,
                          long serverCounter, long serverTotal, long expectedTotal,
                          long catalogSize, long indexed, long uniqueIndexed,
                          long serverRequests, Duration elapsed, long postReopenTotal,
                          List<String> problems) {

        public boolean healthy() {
            return totalViolations == 0
                    && totalFailures == 0
                    && serverTotal == expectedTotal
                    && postReopenTotal == expectedTotal
                    && serverCounter == clientIncrements
                    && catalogSize == indexed
                    && catalogSize == uniqueIndexed
                    && problems.isEmpty();
        }

        @Override
        public String toString() {
            long seconds = Math.max(1, elapsed.toSeconds());
            return """
                    ZeroZ DB multi-JVM stress result
                      topology            : 1 server JVM + %d client JVMs, real TCP
                      elapsed             : %s
                      remote writes       : %d  (%d/s)
                      remote reads        : %d  (%d/s)
                      server requests     : %d
                      unique rejections   : %d
                      invariant violations: %d
                      client failures     : %d
                      money invariant     : server %d == expected %d  %s
                      counter invariant   : server %d == client increments %d  %s
                      index invariant     : catalog %d == byCategory %d == bySku %d  %s
                      durability          : after reopen %d == expected %d  %s%s"""
                    .formatted(clientJvms, elapsed, totalWrites, totalWrites / seconds,
                            totalReads, totalReads / seconds, serverRequests, totalRejections,
                            totalViolations, totalFailures,
                            serverTotal, expectedTotal, mark(serverTotal == expectedTotal),
                            serverCounter, clientIncrements, mark(serverCounter == clientIncrements),
                            catalogSize, indexed, uniqueIndexed,
                            mark(catalogSize == indexed && catalogSize == uniqueIndexed),
                            postReopenTotal, expectedTotal, mark(postReopenTotal == expectedTotal),
                            problems.isEmpty() ? "" : "\n  problems            : " + problems);
        }

        private static String mark(boolean ok) {
            return ok ? "OK" : "*** FAILED ***";
        }
    }

    private final int clientJvms;
    private final int threadsPerJvm;
    private final int seconds;
    private final String durability;

    public MultiJvmStress(int clientJvms, int threadsPerJvm, int seconds, String durability) {
        this.clientJvms = clientJvms;
        this.threadsPerJvm = threadsPerJvm;
        this.seconds = seconds;
        this.durability = durability;
    }

    public Outcome run() throws Exception {
        Path storeDir = Path.of("target", "multijvm-" + System.nanoTime());
        long expectedTotal = (long) ACCOUNTS * OPENING_BALANCE;
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        Process server = new ProcessBuilder(javaBin, "-cp", classpath,
                ServerMain.class.getName(), storeDir.toString(), "0",
                String.valueOf(ACCOUNTS), String.valueOf(OPENING_BALANCE), durability)
                .redirectErrorStream(true)
                .start();

        // The server's stdout must be drained continuously. If it is left unread, the OS pipe
        // buffer fills, the server JVM blocks on its next log write, and it silently stops
        // serving — which looks exactly like a database hang. (Cost us a debugging round.)
        java.util.concurrent.BlockingQueue<String> serverLines =
                new java.util.concurrent.LinkedBlockingQueue<>();
        Thread serverDrain = Thread.ofPlatform().daemon().name("server-drain").start(() -> {
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(server.getInputStream()))) {
                String line;
                while ((line = out.readLine()) != null) {
                    serverLines.add(line);
                }
            } catch (IOException ignored) {
            }
        });
        int port = awaitReady(serverLines, server);

        Instant started = Instant.now();
        List<Process> clients = new ArrayList<>();
        for (int i = 0; i < clientJvms; i++) {
            clients.add(new ProcessBuilder(javaBin, "-cp", classpath,
                    ClientMain.class.getName(), String.valueOf(port),
                    String.valueOf(threadsPerJvm), String.valueOf(seconds),
                    String.valueOf(ACCOUNTS), String.valueOf(expectedTotal))
                    .redirectErrorStream(true)
                    .start());
        }

        List<ClientOutcome> outcomes = collectClients(clients);
        Duration elapsed = Duration.between(started, Instant.now());

        // Closing stdin tells the server to report and exit cleanly.
        server.getOutputStream().close();
        String finalLine = awaitFinal(serverLines);
        server.waitFor(60, TimeUnit.SECONDS);
        server.destroyForcibly();
        serverDrain.interrupt();

        long serverTotal = parseLong(finalLine, "total=");
        long serverCounter = parseLong(finalLine, "counter=");
        long catalog = parseLong(finalLine, "catalog=");
        long indexed = parseLong(finalLine, "indexed=");
        long uniqueIndexed = parseLong(finalLine, "uniqueIndexed=");
        long requests = parseLong(finalLine, "requests=");

        long postReopenTotal = reopenAndSum(storeDir);

        List<String> problems = new ArrayList<>();
        for (ClientOutcome outcome : outcomes) {
            if (outcome.problem() != null) {
                problems.add(outcome.problem());
            }
        }
        if (finalLine == null) {
            problems.add("server did not report final state");
        }

        return new Outcome(clientJvms,
                sum(outcomes, ClientOutcome::writes), sum(outcomes, ClientOutcome::reads),
                sum(outcomes, ClientOutcome::rejections), sum(outcomes, ClientOutcome::violations),
                sum(outcomes, ClientOutcome::failures), sum(outcomes, ClientOutcome::increments),
                serverCounter, serverTotal, expectedTotal,
                catalog, indexed, uniqueIndexed, requests, elapsed, postReopenTotal, problems);
    }

    private static long reopenAndSum(Path storeDir) {
        try (com.zeroz4j.db.ZeroZDb db = com.zeroz4j.db.ZeroZDb.open(new BankRoot(), storeDir)) {
            BankRoot root = db.root();
            return db.read(() -> root.accounts.values().stream()
                    .mapToLong(a -> a.balanceCents).sum());
        }
    }

    private List<ClientOutcome> collectClients(List<Process> clients) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        ConcurrentHashMap<Integer, ClientOutcome> results = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            int index = i;
            Process client = clients.get(i);
            pool.submit(() -> {
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(client.getInputStream()))) {
                    String resultLine = null;
                    List<String> tail = new ArrayList<>();
                    String line;
                    while ((line = out.readLine()) != null) {
                        if (line.startsWith("RESULT ")) {
                            resultLine = line;
                        } else if (!line.startsWith("WARNING") && !line.isBlank()) {
                            tail.add(line);
                            if (tail.size() > 4) {
                                tail.remove(0);
                            }
                        }
                    }
                    int exit = client.waitFor();
                    ClientOutcome outcome = parseClient(resultLine, exit);
                    if (resultLine == null && !tail.isEmpty()) {
                        outcome = new ClientOutcome(0, 0, 0, 0, 1, 0,
                                "client JVM " + index + " output: " + tail, exit);
                    }
                    results.put(index, outcome);
                } catch (Exception e) {
                    results.put(index, new ClientOutcome(0, 0, 0, 0, 1, 0,
                            "client JVM " + index + " unreadable: " + e, -1));
                } finally {
                    done.countDown();
                }
            });
        }
        done.await(seconds + 180L, TimeUnit.SECONDS);
        pool.shutdownNow();
        List<ClientOutcome> ordered = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            ordered.add(results.getOrDefault(i, new ClientOutcome(0, 0, 0, 0, 1, 0,
                    "client JVM " + i + " never reported", -1)));
        }
        return ordered;
    }

    private static ClientOutcome parseClient(String line, int exit) {
        if (line == null) {
            return new ClientOutcome(0, 0, 0, 0, 1, 0, "client produced no RESULT line", exit);
        }
        String problem = line.contains(" problem=")
                ? line.substring(line.indexOf(" problem=") + 9) : null;
        return new ClientOutcome(parseLong(line, "writes="), parseLong(line, "reads="),
                parseLong(line, "rejections="), parseLong(line, "violations="),
                parseLong(line, "failures="), parseLong(line, "increments="), problem, exit);
    }

    private static int awaitReady(java.util.concurrent.BlockingQueue<String> lines, Process server)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            String line = lines.poll(5, TimeUnit.SECONDS);
            if (line != null && line.startsWith("READY ")) {
                return Integer.parseInt(line.substring(6).trim());
            }
            if (line == null && !server.isAlive()) {
                break;
            }
        }
        server.destroyForcibly();
        throw new IllegalStateException("Server JVM never became ready");
    }

    private static String awaitFinal(java.util.concurrent.BlockingQueue<String> lines)
            throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(deadline)) {
            String line = lines.poll(5, TimeUnit.SECONDS);
            if (line != null && line.startsWith("FINAL ")) {
                return line;
            }
        }
        return null;
    }

    private static long parseLong(String line, String key) {
        if (line == null) {
            return -1;
        }
        int start = line.indexOf(key);
        if (start < 0) {
            return -1;
        }
        start += key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) {
            end++;
        }
        return end == start ? -1 : Long.parseLong(line.substring(start, end));
    }

    private static long sum(List<ClientOutcome> outcomes,
                            java.util.function.ToLongFunction<ClientOutcome> field) {
        return outcomes.stream().mapToLong(field).sum();
    }

    public static void main(String[] args) throws Exception {
        int jvms = args.length > 0 ? Integer.parseInt(args[0]) : 4;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        String durability = args.length > 3 ? args[3] : "SYNC";
        System.out.println("clientJvms=" + jvms + " threadsPerJvm=" + threads
                + " seconds=" + seconds + " durability=" + durability);
        Outcome outcome = new MultiJvmStress(jvms, threads, seconds, durability).run();
        System.out.println(outcome);
        System.exit(outcome.healthy() ? 0 : 1);
    }
}
