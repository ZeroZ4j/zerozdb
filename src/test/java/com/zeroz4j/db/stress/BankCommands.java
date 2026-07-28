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

import com.zeroz4j.db.WriteContext;
import com.zeroz4j.db.net.DbCommand;
import com.zeroz4j.db.net.DbQuery;

/**
 * The remote workload: commands and queries shipped from client JVMs and executed on the server
 * against the live graph. Each carries only parameters — no object references cross the wire.
 */
public final class BankCommands {

    /** Moves money between two accounts in one atomic block. Total must be conserved. */
    public static final class Transfer implements DbCommand<Boolean> {
        public long fromId;
        public long toId;
        public long amount;

        public Transfer() {
        }

        public Transfer(long fromId, long toId, long amount) {
            this.fromId = fromId;
            this.toId = toId;
            this.amount = amount;
        }

        @Override
        public Boolean execute(WriteContext ctx, Object root) {
            BankRoot bank = (BankRoot) root;
            Account from = bank.accounts.get(fromId);
            Account to = bank.accounts.get(toId);
            if (from == null || to == null || from.balanceCents < amount) {
                return Boolean.FALSE;
            }
            ctx.edit(from);
            ctx.edit(to);
            from.balanceCents -= amount;
            to.balanceCents += amount;
            return Boolean.TRUE;
        }
    }

    /** Increments the shared counter. Server-side, so no optimistic retry is needed. */
    public static final class Increment implements DbCommand<Long> {
        @Override
        public Long execute(WriteContext ctx, Object root) {
            BankRoot bank = (BankRoot) root;
            ctx.edit(bank.counter);
            return ++bank.counter.value;
        }
    }

    /** Adds a product; may violate the unique SKU index, which must abort the whole command. */
    public static final class AddProduct implements DbCommand<Long> {
        public String sku;
        public String category;

        public AddProduct() {
        }

        public AddProduct(String sku, String category) {
            this.sku = sku;
            this.category = category;
        }

        @Override
        public Long execute(WriteContext ctx, Object root) {
            BankRoot bank = (BankRoot) root;
            ctx.edit(bank);
            ctx.edit(bank.catalog);
            long id = bank.nextProductId++;
            bank.catalog.put(id, new StressProduct(id, sku, category));
            return id;
        }
    }

    /** The invariant probe: the sum of all balances, computed inside a server read-block. */
    public static final class TotalBalance implements DbQuery<Long> {
        @Override
        public Long execute(Object root) {
            return ((BankRoot) root).accounts.values().stream()
                    .mapToLong(a -> a.balanceCents).sum();
        }
    }

    public static final class CounterValue implements DbQuery<Long> {
        @Override
        public Long execute(Object root) {
            return ((BankRoot) root).counter.value;
        }
    }

    public static final class CatalogSize implements DbQuery<Integer> {
        @Override
        public Integer execute(Object root) {
            return ((BankRoot) root).catalog.size();
        }
    }

    /** Seeds accounts; run once by the server before clients start. */
    public static final class Seed implements DbCommand<Integer> {
        public int accounts;
        public long openingBalance;

        public Seed() {
        }

        public Seed(int accounts, long openingBalance) {
            this.accounts = accounts;
            this.openingBalance = openingBalance;
        }

        @Override
        public Integer execute(WriteContext ctx, Object root) {
            BankRoot bank = (BankRoot) root;
            ctx.edit(bank.accounts);
            for (long id = 0; id < accounts; id++) {
                bank.accounts.put(id, new Account(id, openingBalance));
            }
            ctx.store(bank);
            return accounts;
        }
    }

    private BankCommands() {
    }
}
