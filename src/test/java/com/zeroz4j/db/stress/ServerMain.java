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
import com.zeroz4j.db.Index;
import com.zeroz4j.db.UniqueIndex;
import com.zeroz4j.db.ZeroZDb;
import com.zeroz4j.db.net.ZeroZDbServer;

import java.nio.file.Path;

/**
 * The server JVM of the multi-JVM stress run: owns the store exclusively, serves clients, and
 * on shutdown prints the final invariant state for the parent to verify.
 * <p>
 * Args: {@code <storeDir> <port> <accounts> <openingBalance> [SYNC|OS_BUFFERED]}.
 * Prints {@code READY <port>} once accepting, then blocks until stdin closes or SIGTERM.
 */
public final class ServerMain {

    public static void main(String[] args) throws Exception {
        Path storeDir = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        int accounts = Integer.parseInt(args[2]);
        long opening = Long.parseLong(args[3]);
        Durability durability = args.length > 4 ? Durability.valueOf(args[4]) : Durability.SYNC;

        try (ZeroZDb db = ZeroZDb.open(new BankRoot(), storeDir, durability)) {
            BankRoot root = db.root();
            db.write(ctx -> {
                ctx.edit(root.accounts);
                for (long id = 0; id < accounts; id++) {
                    root.accounts.put(id, new Account(id, opening));
                }
                ctx.store(root);
            });
            Index<String, StressProduct> byCategory = db.index("byCategory", StressProduct.class,
                    () -> ((BankRoot) db.root()).catalog, p -> p.category);
            UniqueIndex<String, StressProduct> bySku = db.uniqueIndex("bySku", StressProduct.class,
                    () -> ((BankRoot) db.root()).catalog, p -> p.sku);

            try (ZeroZDbServer server = ZeroZDbServer.builder()
                    .store("bank", db)
                    .schemaId("stress-v1")
                    .port(port)
                    .start()) {

                System.out.println("READY " + server.port());
                System.out.flush();

                // Block until the parent closes stdin.
                while (System.in.read() != -1) {
                    // drain
                }

                long total = db.read(() -> root.accounts.values().stream()
                        .mapToLong(a -> a.balanceCents).sum());
                System.out.println("FINAL total=" + total
                        + " counter=" + db.read(() -> root.counter.value)
                        + " catalog=" + db.read(() -> root.catalog.size())
                        + " indexed=" + byCategory.size()
                        + " uniqueIndexed=" + bySku.size()
                        + " requests=" + server.requestsServed());
                System.out.flush();
            }
        }
    }
}
