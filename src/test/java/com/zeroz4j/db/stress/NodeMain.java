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

import com.zeroz4j.db.Durability;
import com.zeroz4j.db.net.ZeroZDbNode;

import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A JVM that joins a store in auto-server mode and keeps working through owner changes. Used by
 * the failover test: kill whichever JVM currently owns the store and the survivors must elect a
 * new owner among themselves and continue without losing acknowledged writes.
 * <p>
 * Args: {@code <storeDir> <accounts> <seconds> [SYNC|OS_BUFFERED]}. Prints {@code JOINED owner=…}
 * once, {@code PROMOTED} if it takes over, and {@code RESULT acked=… failures=… owner=…} at the end.
 */
public final class NodeMain {

    public static void main(String[] args) throws Exception {
        Path storeDir = Path.of(args[0]);
        int accounts = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        Durability durability = args.length > 3 ? Durability.valueOf(args[3]) : Durability.SYNC;

        AtomicLong acked = new AtomicLong();
        AtomicLong maxSeen = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        String problem = null;
        boolean announcedPromotion = false;

        try (ZeroZDbNode node = ZeroZDbNode.builder(storeDir, BankRoot::new)
                .storeName("bank").schemaId("failover-v1").durability(durability).build()) {

            System.out.println("JOINED owner=" + node.isOwner() + " pid="
                    + ProcessHandle.current().pid());
            System.out.flush();

            if (node.isOwner()) {
                node.execute(new BankCommands.Seed(accounts, 100_000));
            }

            long deadline = System.currentTimeMillis() + seconds * 1000L;
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (System.currentTimeMillis() < deadline) {
                try {
                    long value = node.execute(new BankCommands.Increment());
                    acked.incrementAndGet();
                    maxSeen.accumulateAndGet(value, Math::max);
                    if (node.isOwner() && !announcedPromotion) {
                        announcedPromotion = true;
                        System.out.println("PROMOTED after " + acked.get() + " acks");
                        System.out.flush();
                    }
                    Thread.sleep(rnd.nextInt(2, 8));
                } catch (Exception e) {
                    failures.incrementAndGet();
                    if (problem == null) {
                        problem = e.getClass().getName() + ": " + e.getMessage();
                    }
                }
            }

            System.out.println("RESULT acked=" + acked.get()
                    + " maxSeen=" + maxSeen.get()
                    + " failures=" + failures.get()
                    + " owner=" + node.isOwner()
                    + (problem == null ? "" : " problem=" + problem));
            System.out.flush();
        }
        System.exit(0);
    }
}
