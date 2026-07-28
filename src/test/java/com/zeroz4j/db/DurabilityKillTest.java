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

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The M0 durability proof: hard-kill a JVM mid-write and verify every acknowledged write
 * survived. (A process kill proves engine-level atomicity/durability; OS-level power-cut fsync
 * semantics remain open verification item V1 in the design doc.)
 */
class DurabilityKillTest {

    private static final int ACKS_REQUIRED = 20;

    @Test
    void everyAcknowledgedWriteSurvivesProcessKill() throws Exception {
        Path dir = TestStores.newDir("kill");

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(
                javaBin, "-cp", System.getProperty("java.class.path"),
                KillTestChild.class.getName(), dir.toAbsolutePath().toString())
                .redirectErrorStream(true)
                .start();

        int lastAck = -1;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
            String line;
            while (lastAck < ACKS_REQUIRED && (line = out.readLine()) != null) {
                if (line.startsWith("ACK ")) {
                    lastAck = Integer.parseInt(line.substring(4).trim());
                }
            }
            assertTrue(lastAck >= ACKS_REQUIRED, "child never reached " + ACKS_REQUIRED
                    + " acks; last output line seen: " + lastAck);
            child.destroyForcibly();
            child.waitFor();
        }

        ZeroZDb db = reopenWithRetry(dir);
        try {
            TestRoot root = db.root();
            for (int i = 0; i <= lastAck; i++) {
                assertEquals("v" + i, root.entries.get("k" + i),
                        "acked entry k" + i + " was lost by the kill");
            }
        } finally {
            db.close();
        }
    }

    private static ZeroZDb reopenWithRetry(Path dir) throws InterruptedException {
        StoreOwnedException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return ZeroZDb.open(new TestRoot(), dir);
            } catch (StoreOwnedException e) {
                last = e;
                Thread.sleep(250);
            }
        }
        throw last;
    }
}
