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

import com.zeroz4j.db.StoreOwnedException;
import com.zeroz4j.db.StoreOwnership;

import java.nio.file.Path;

/**
 * Ownership by OS file lock — the default, and the right choice whenever the store is on a
 * local disk (including a Kubernetes ReadWriteOnce volume, which mounts on one node only).
 * <p>
 * Takeover is instant because the kernel releases the lock when a process dies, and there is no
 * heartbeat to tune. The limitation is that file locking on network filesystems (NFS, SMB) is
 * unreliable — for those, use {@link LeaseFileArbiter}.
 */
public final class FileLockArbiter implements OwnershipArbiter {

    @Override
    public Lease tryAcquire(Path storeDir, String ownerId) {
        try {
            StoreOwnership ownership = StoreOwnership.acquire(storeDir);
            return new Lease() {
                private volatile boolean held = true;

                @Override
                public long epoch() {
                    return 0;      // an exclusive OS lock needs no fencing token
                }

                @Override
                public boolean isValid() {
                    return held;
                }

                @Override
                public void onLost(Runnable action) {
                    // A kernel-held lock is never lost while the process lives.
                }

                @Override
                public void close() {
                    held = false;
                    ownership.release();
                }
            };
        } catch (StoreOwnedException e) {
            return null;
        }
    }
}
