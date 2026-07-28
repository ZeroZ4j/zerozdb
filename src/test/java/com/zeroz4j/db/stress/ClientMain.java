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

import com.zeroz4j.db.UniqueConstraintException;
import com.zeroz4j.db.net.ZeroZDbClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A client JVM of the multi-JVM stress run: connects N times to the server and drives mixed
 * workloads from Loom virtual threads, checking the money invariant on every read it performs.
 * <p>
 * Args: {@code <port> <threads> <seconds> <accounts> <expectedTotal>}.
 * Prints {@code RESULT writes=… reads=… rejections=… violations=… failures=…} on exit.
 */
public final class ClientMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int threads = Integer.parseInt(args[1]);
        int seconds = Integer.parseInt(args[2]);
        int accounts = Integer.parseInt(args[3]);
        long expectedTotal = Long.parseLong(args[4]);

        AtomicLong writes = new AtomicLong();
        AtomicLong reads = new AtomicLong();
        AtomicLong rejections = new AtomicLong();
        AtomicLong violations = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        AtomicLong increments = new AtomicLong();
        AtomicReference<String> firstProblem = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();

        List<ZeroZDbClient> clients = ZeroZDbClient.connectMany(
                "127.0.0.1", port, "stress-v1", threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            int index = i;
            ZeroZDbClient client = clients.get(i);
            workers.add(Thread.ofVirtual().name("stress-client-" + index).start(() -> {
                try {
                    go.await();
                    ThreadLocalRandom rnd = ThreadLocalRandom.current();
                    while (!stop.get()) {
                        switch (index % 4) {
                            case 0, 3 -> {
                                long from = rnd.nextLong(accounts);
                                long to = rnd.nextLong(accounts);
                                if (from != to) {
                                    client.execute("bank", new BankCommands.Transfer(
                                            from, to, rnd.nextLong(1, 500)));
                                    writes.incrementAndGet();
                                }
                            }
                            case 1 -> {
                                long total = client.query("bank", new BankCommands.TotalBalance());
                                reads.incrementAndGet();
                                if (total != expectedTotal) {
                                    violations.incrementAndGet();
                                    firstProblem.compareAndSet(null,
                                            "money invariant broken: " + total + " != " + expectedTotal);
                                    stop.set(true);
                                }
                            }
                            case 2 -> {
                                client.execute("bank", new BankCommands.Increment());
                                writes.incrementAndGet();
                                increments.incrementAndGet();
                                try {
                                    client.execute("bank", new BankCommands.AddProduct(
                                            "SKU-" + rnd.nextInt(30), "cat-" + rnd.nextInt(6)));
                                    writes.incrementAndGet();
                                } catch (UniqueConstraintException e) {
                                    rejections.incrementAndGet();
                                }
                            }
                            default -> {
                            }
                        }
                    }
                } catch (Throwable t) {
                    failures.incrementAndGet();
                    firstProblem.compareAndSet(null, t.getClass().getName() + ": " + t.getMessage());
                }
            }));
        }

        go.countDown();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        clients.forEach(ZeroZDbClient::close);

        System.out.println("RESULT writes=" + writes.get()
                + " reads=" + reads.get()
                + " rejections=" + rejections.get()
                + " violations=" + violations.get()
                + " failures=" + failures.get()
                + " increments=" + increments.get()
                + (firstProblem.get() == null ? "" : " problem=" + firstProblem.get()));
        System.out.flush();
        System.exit(violations.get() == 0 && failures.get() == 0 ? 0 : 1);
    }
}
