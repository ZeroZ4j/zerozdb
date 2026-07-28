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

import java.nio.file.Path;

/**
 * Decides which process may own a store. Pluggable because the right mechanism depends on where
 * the store lives:
 * <ul>
 *   <li>{@link FileLockArbiter} — an OS file lock. Correct and instant on a local disk; the
 *       kernel releases it on process death. <strong>Not</strong> trustworthy on NFS/SMB.</li>
 *   <li>{@link LeaseFileArbiter} — a lease file with expiry and a fencing epoch, renewed by a
 *       heartbeat. Works wherever atomic file replacement works, including shared volumes, at
 *       the cost of a takeover delay.</li>
 * </ul>
 * A future implementation can back the same interface with a PostgreSQL advisory lock, a
 * Kubernetes Lease object, or Infinispan without touching the engine.
 */
public interface OwnershipArbiter {

    /**
     * Attempts to become the store's owner.
     *
     * @return the held lease, or {@code null} if another process owns the store
     */
    Lease tryAcquire(Path storeDir, String ownerId);

    /**
     * A held claim on a store. Ownership is valid only while {@link #isValid()} is true — an
     * owner that loses its lease (heartbeat starved, clock jump, partition) must stop serving,
     * which is what makes takeover safe.
     */
    interface Lease extends AutoCloseable {

        /**
         * Monotonically increasing across successive owners. A store's data can be tagged with
         * the epoch that wrote it, so a resurrected zombie owner is detectable.
         */
        long epoch();

        boolean isValid();

        /** Called when the lease is lost, so the owner can step down. May be called once. */
        void onLost(Runnable action);

        @Override
        void close();
    }
}
