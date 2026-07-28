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
package com.zeroz4j.db.lease;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseArbiterTest {

    private static Path dir(String name) {
        return Path.of("target", "test-stores", name + "-" + System.nanoTime());
    }

    private static LeaseFileArbiter fastArbiter() {
        return new LeaseFileArbiter(Duration.ofMillis(200), Duration.ofMillis(600),
                Duration.ofMillis(200));
    }

    @Test
    void aLiveLeaseBlocksOtherProcesses() {
        Path storeDir = dir("lease-block");
        LeaseFileArbiter arbiter = fastArbiter();
        try (OwnershipArbiter.Lease held = arbiter.tryAcquire(storeDir, "process-A")) {
            assertNotNull(held);
            assertTrue(held.isValid());
            assertNull(arbiter.tryAcquire(storeDir, "process-B"),
                    "a live lease must not be stealable");
        }
    }

    @Test
    void releasedLeaseIsTakenOverImmediatelyWithHigherEpoch() {
        Path storeDir = dir("lease-handover");
        LeaseFileArbiter arbiter = fastArbiter();
        long firstEpoch;
        try (OwnershipArbiter.Lease first = arbiter.tryAcquire(storeDir, "process-A")) {
            firstEpoch = first.epoch();
        }
        try (OwnershipArbiter.Lease second = arbiter.tryAcquire(storeDir, "process-B")) {
            assertNotNull(second, "a released lease must be acquirable at once");
            assertTrue(second.epoch() > firstEpoch,
                    "each ownership generation gets a higher epoch (fencing token)");
        }
    }

    @Test
    void abandonedLeaseExpiresAndIsTakenOver() throws Exception {
        Path storeDir = dir("lease-expire");
        LeaseFileArbiter arbiter = fastArbiter();

        // Simulate a dead owner: write a lease nobody renews.
        LeaseFileArbiter.write(storeDir,
                new LeaseFileArbiter.LeaseRecord("dead-process", 7,
                        System.currentTimeMillis() + 300));

        assertNull(arbiter.tryAcquire(storeDir, "process-B"),
                "must not steal a lease that has not expired yet");

        Thread.sleep(500);
        try (OwnershipArbiter.Lease taken = arbiter.tryAcquire(storeDir, "process-B")) {
            assertNotNull(taken, "an expired lease must be takeable");
            assertTrue(taken.epoch() > 7);
        }
    }

    @Test
    void ownerLearnsItLostTheLeaseAndIsToldToStepDown() throws Exception {
        Path storeDir = dir("lease-lost");
        LeaseFileArbiter arbiter = fastArbiter();
        java.util.concurrent.CountDownLatch lost = new java.util.concurrent.CountDownLatch(1);

        try (OwnershipArbiter.Lease held = arbiter.tryAcquire(storeDir, "process-A")) {
            assertNotNull(held);
            held.onLost(lost::countDown);

            // Another process force-takes the store (as it would after observing an expiry).
            LeaseFileArbiter.write(storeDir,
                    new LeaseFileArbiter.LeaseRecord("process-B", held.epoch() + 1,
                            System.currentTimeMillis() + 10_000));

            assertTrue(lost.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "the displaced owner must be notified so it can stop serving");
            assertTrue(!held.isValid(), "a displaced lease is no longer valid");
        }
    }

    @Test
    void fileLockArbiterStillGuardsLocalStores() {
        Path storeDir = dir("lease-filelock");
        FileLockArbiter arbiter = new FileLockArbiter();
        try (OwnershipArbiter.Lease held = arbiter.tryAcquire(storeDir, "process-A")) {
            assertNotNull(held);
            assertNull(arbiter.tryAcquire(storeDir, "process-B"));
        }
        try (OwnershipArbiter.Lease again = arbiter.tryAcquire(storeDir, "process-B")) {
            assertNotNull(again, "released file lock is immediately reusable");
        }
    }
}
