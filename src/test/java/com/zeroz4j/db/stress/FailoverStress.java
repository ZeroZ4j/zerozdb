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

import com.zeroz4j.db.ZeroZDb;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hard-kills the JVM that owns a live store and proves the survivors elect a new owner, keep
 * serving, and lose no acknowledged write.
 * <p>
 * The invariant is exact. Every {@code Increment} returns the counter value it produced, so each
 * node remembers the highest value it ever had <em>acknowledged</em>. After every JVM is gone,
 * the store is opened directly and its counter must be at least that high — if a promotion had
 * resurrected an older state, the counter would have gone backwards and a client would have been
 * told about a write that no longer exists.
 * <p>
 * Runnable standalone: {@code java -cp <test-cp> com.zeroz4j.db.stress.FailoverStress [nodes] [seconds]}
 */
public final class FailoverStress {

    private static final int ACCOUNTS = 50;

    public record Outcome(int nodes, long survivorAcks, long highestAcknowledged,
                          long finalCounter, boolean someoneWasPromoted, List<String> problems) {

        public boolean healthy() {
            return problems.isEmpty()
                    && someoneWasPromoted
                    && survivorAcks > 0
                    && finalCounter >= highestAcknowledged;
        }

        @Override
        public String toString() {
            return """
                    ZeroZ DB failover result
                      nodes                : %d JVMs sharing one store (auto-server mode)
                      owner killed         : hard kill, mid-flight
                      survivor acks        : %d
                      promotion happened   : %s
                      highest acked value  : %d
                      counter after reopen : %d  %s%s"""
                    .formatted(nodes, survivorAcks, someoneWasPromoted ? "yes" : "*** NO ***",
                            highestAcknowledged, finalCounter,
                            finalCounter >= highestAcknowledged
                                    ? "OK (no acknowledged write lost)"
                                    : "*** LOST ACKNOWLEDGED WRITES ***",
                            problems.isEmpty() ? "" : "\n  problems             : " + problems);
        }
    }

    private final int nodes;
    private final int seconds;

    public FailoverStress(int nodes, int seconds) {
        this.nodes = nodes;
        this.seconds = seconds;
    }

    public Outcome run() throws Exception {
        Path storeDir = Path.of("target", "failover-" + System.nanoTime());
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> problems = new ArrayList<>();
        List<Process> processes = new ArrayList<>();
        List<BlockingQueue<String>> outputs = new ArrayList<>();

        // Start the owner alone so ownership is unambiguous, then the clients.
        processes.add(start(javaBin, classpath, storeDir));
        outputs.add(drain(processes.get(0)));
        String firstJoin = awaitLine(outputs.get(0), "JOINED", 90);
        if (firstJoin == null || !firstJoin.contains("owner=true")) {
            processes.forEach(Process::destroyForcibly);
            throw new IllegalStateException("First node did not become owner: " + firstJoin);
        }

        for (int i = 1; i < nodes; i++) {
            Process process = start(javaBin, classpath, storeDir);
            processes.add(process);
            BlockingQueue<String> output = drain(process);
            outputs.add(output);
            String join = awaitLine(output, "JOINED", 90);
            if (join == null || !join.contains("owner=false")) {
                problems.add("node " + i + " joined unexpectedly: " + join);
            }
        }

        Thread.sleep(seconds * 1000L / 3);
        processes.get(0).destroyForcibly();          // kill the owner mid-flight
        processes.get(0).waitFor(30, TimeUnit.SECONDS);

        long survivorAcks = 0;
        long highestAcknowledged = 0;
        boolean promoted = false;
        for (int i = 1; i < processes.size(); i++) {
            String result = awaitLine(outputs.get(i), "RESULT", seconds + 180);
            if (result == null) {
                problems.add("node " + i + " never reported");
                continue;
            }
            survivorAcks += parseLong(result, "acked=");
            highestAcknowledged = Math.max(highestAcknowledged, parseLong(result, "maxSeen="));
            if (result.contains(" problem=")) {
                problems.add("node " + i + ": " + result.substring(result.indexOf(" problem=") + 9));
            }
            promoted |= result.contains("owner=true");
            processes.get(i).waitFor(60, TimeUnit.SECONDS);
        }
        processes.forEach(Process::destroyForcibly);

        long finalCounter = readCounter(storeDir);
        return new Outcome(nodes, survivorAcks, highestAcknowledged, finalCounter,
                promoted, problems);
    }

    /** Opens the store directly — valid only once every node JVM has exited. */
    private static long readCounter(Path storeDir) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try (ZeroZDb db = ZeroZDb.open(new BankRoot(), storeDir)) {
                BankRoot root = db.root();
                return db.read(() -> root.counter.value);
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw last;
    }

    private Process start(String javaBin, String classpath, Path storeDir) throws IOException {
        return new ProcessBuilder(javaBin, "-cp", classpath, NodeMain.class.getName(),
                storeDir.toString(), String.valueOf(ACCOUNTS), String.valueOf(seconds), "SYNC")
                .redirectErrorStream(true)
                .start();
    }

    private static BlockingQueue<String> drain(Process process) {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
            }
        });
        return lines;
    }

    private static String awaitLine(BlockingQueue<String> lines, String prefix, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(2, TimeUnit.SECONDS);
            if (line != null && line.startsWith(prefix)) {
                return line;
            }
        }
        return null;
    }

    private static long parseLong(String line, String key) {
        int start = line.indexOf(key);
        if (start < 0) {
            return 0;
        }
        start += key.length();
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        return end == start ? 0 : Long.parseLong(line.substring(start, end));
    }

    public static void main(String[] args) throws Exception {
        int nodes = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        System.out.println("nodes=" + nodes + " seconds=" + seconds);
        Outcome outcome = new FailoverStress(nodes, seconds).run();
        System.out.println(outcome);
        System.exit(outcome.healthy() ? 0 : 1);
    }
}
