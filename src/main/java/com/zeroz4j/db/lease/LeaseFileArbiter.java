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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ownership by renewable lease file — the cross-host mechanism, for stores on shared volumes
 * where OS file locks cannot be trusted.
 * <p>
 * The owner writes {@code zerozdb.lease} holding its id, an epoch and an expiry, and renews it
 * on a heartbeat. Another process may take over only after observing the lease expired for a
 * full grace period, and it takes over by writing a lease with {@code epoch + 1}. The previous
 * owner detects the epoch change (or its own renewal failure) and <strong>steps down</strong>,
 * which is what makes this safe rather than merely optimistic.
 *
 * <h2>What this guarantees, and what it does not</h2>
 * With a heartbeat of {@code h} and a lease duration of {@code d = 3h}, a new owner appears no
 * sooner than {@code d + grace} after the old one stopped renewing, and the old owner stops
 * serving within one heartbeat of losing the lease. That leaves a window only if a process is
 * frozen (long GC pause, VM suspend, SIGSTOP) for longer than {@code d} <em>and</em> resumes
 * mid-write. Closing that window entirely requires the storage layer itself to reject writes
 * carrying a stale epoch — true fencing, which EclipseStore's file format does not offer.
 * <p>
 * So: this is a correct, conservative lease with prompt self-fencing, not an absolute guarantee
 * against a pathologically-frozen process. It is the same trade every lease-based leader
 * election makes without storage-level fencing tokens, and it must be documented rather than
 * glossed over.
 */
public final class LeaseFileArbiter implements OwnershipArbiter {

    static final String FILE_NAME = "zerozdb.lease";

    private final Duration heartbeat;
    private final Duration leaseDuration;
    private final Duration grace;

    public LeaseFileArbiter() {
        this(Duration.ofSeconds(2), Duration.ofSeconds(6), Duration.ofSeconds(2));
    }

    public LeaseFileArbiter(Duration heartbeat, Duration leaseDuration, Duration grace) {
        if (leaseDuration.compareTo(heartbeat.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be at least twice the heartbeat, else a single missed "
                            + "renewal loses ownership");
        }
        this.heartbeat = heartbeat;
        this.leaseDuration = leaseDuration;
        this.grace = grace;
    }

    @Override
    public Lease tryAcquire(Path storeDir, String ownerId) {
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot prepare store directory " + storeDir, e);
        }
        LeaseRecord existing = read(storeDir);
        long now = System.currentTimeMillis();

        if (existing != null && !existing.ownerId().equals(ownerId)) {
            if (now < existing.expiresAt()) {
                return null;                                   // a live owner holds it
            }
            // Expired: wait out the grace period and re-check, so two challengers racing after
            // an owner's death do not both conclude "it's mine" from the same stale read.
            sleep(grace.toMillis());
            LeaseRecord recheck = read(storeDir);
            if (recheck != null && !recheck.ownerId().equals(ownerId)
                    && System.currentTimeMillis() < recheck.expiresAt()) {
                return null;                                   // someone else got there first
            }
            existing = recheck;
        }

        long epoch = existing == null ? 1 : existing.epoch() + 1;
        write(storeDir, new LeaseRecord(ownerId, epoch,
                System.currentTimeMillis() + leaseDuration.toMillis()));

        // Confirm the write won: a competing challenger may have overwritten us in the same
        // instant. Whoever's record survives a short settle owns the store.
        sleep(Math.min(250, heartbeat.toMillis()));
        LeaseRecord confirmed = read(storeDir);
        if (confirmed == null || !confirmed.ownerId().equals(ownerId)) {
            return null;
        }
        return new FileLease(storeDir, ownerId, confirmed.epoch());
    }

    /**
     * Seizes ownership without waiting for the current lease to expire, by writing a lease with
     * a higher epoch. The incumbent notices within one heartbeat and steps down.
     * <p>
     * <strong>Operator tool, not a normal path.</strong> Use only when the previous owner is
     * known to be gone (a crashed pod that will not return, a host that has been destroyed) and
     * waiting out the lease is unacceptable. Calling this while the incumbent is alive and
     * writing shortens the safety margin to a single heartbeat.
     */
    public Lease forceAcquire(Path storeDir, String ownerId) {
        LeaseRecord existing = read(storeDir);
        long epoch = existing == null ? 1 : existing.epoch() + 1;
        write(storeDir, new LeaseRecord(ownerId, epoch,
                System.currentTimeMillis() + leaseDuration.toMillis()));
        return new FileLease(storeDir, ownerId, epoch);
    }

    private final class FileLease implements Lease {
        private final Path storeDir;
        private final String ownerId;
        private final long epoch;
        private final Thread heartbeatThread;
        private final AtomicReference<Runnable> onLost = new AtomicReference<>();
        private volatile boolean valid = true;
        private volatile boolean closed;

        FileLease(Path storeDir, String ownerId, long epoch) {
            this.storeDir = storeDir;
            this.ownerId = ownerId;
            this.epoch = epoch;
            this.heartbeatThread = Thread.ofPlatform().daemon()
                    .name("zerozdb-lease-" + storeDir.getFileName())
                    .start(this::heartbeatLoop);
        }

        private void heartbeatLoop() {
            while (!closed) {
                sleep(heartbeat.toMillis());
                if (closed) {
                    return;
                }
                LeaseRecord current = read(storeDir);
                if (current != null
                        && (!current.ownerId().equals(ownerId) || current.epoch() > epoch)) {
                    lose();                                    // another process took over
                    return;
                }
                try {
                    write(storeDir, new LeaseRecord(ownerId, epoch,
                            System.currentTimeMillis() + leaseDuration.toMillis()));
                } catch (RuntimeException e) {
                    lose();                                    // cannot renew: assume lost
                    return;
                }
            }
        }

        private void lose() {
            valid = false;
            Runnable action = onLost.getAndSet(null);
            if (action != null) {
                action.run();
            }
        }

        @Override
        public long epoch() {
            return epoch;
        }

        @Override
        public boolean isValid() {
            return valid;
        }

        @Override
        public void onLost(Runnable action) {
            onLost.set(action);
            if (!valid) {
                lose();
            }
        }

        @Override
        public void close() {
            closed = true;
            valid = false;
            heartbeatThread.interrupt();
            // Expire the lease immediately so a standby can take over without waiting.
            LeaseRecord current = read(storeDir);
            if (current != null && current.ownerId().equals(ownerId)) {
                try {
                    write(storeDir, new LeaseRecord(ownerId, epoch, 0));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    record LeaseRecord(String ownerId, long epoch, long expiresAt) {
    }

    static LeaseRecord read(Path storeDir) {
        Path file = storeDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            return null;
        }
        String owner = properties.getProperty("ownerId");
        if (owner == null) {
            return null;
        }
        return new LeaseRecord(owner,
                Long.parseLong(properties.getProperty("epoch", "0")),
                Long.parseLong(properties.getProperty("expiresAt", "0")));
    }

    static void write(Path storeDir, LeaseRecord record) {
        Properties properties = new Properties();
        properties.setProperty("ownerId", record.ownerId());
        properties.setProperty("epoch", Long.toString(record.epoch()));
        properties.setProperty("expiresAt", Long.toString(record.expiresAt()));
        Path target = storeDir.resolve(FILE_NAME);
        Path temp = storeDir.resolve(FILE_NAME + "." + record.ownerId() + ".tmp");
        try {
            Files.createDirectories(storeDir);
            try (var out = Files.newOutputStream(temp)) {
                properties.store(out, "ZeroZ DB ownership lease");
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write lease for " + storeDir, e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
