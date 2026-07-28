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
package com.zeroz4j.db.net;

import com.zeroz4j.db.TestRoot;
import com.zeroz4j.db.WriteContext;

/** Commands and queries shared by the network tests and the multi-JVM stress harness. */
public final class Commands {

    public static final class Put implements DbCommand<String> {
        public String key;
        public String value;

        public Put() {
        }

        public Put(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String execute(WriteContext ctx, Object root) {
            TestRoot testRoot = (TestRoot) root;
            ctx.edit(testRoot.entries);
            return testRoot.entries.put(key, value);
        }
    }

    public static final class Get implements DbQuery<String> {
        public String key;

        public Get() {
        }

        public Get(String key) {
            this.key = key;
        }

        @Override
        public String execute(Object root) {
            return ((TestRoot) root).entries.get(key);
        }
    }

    public static final class Size implements DbQuery<Integer> {
        @Override
        public Integer execute(Object root) {
            return ((TestRoot) root).entries.size();
        }
    }

    /** Writes two fields to the same value: a replica must never observe them differing. */
    public static final class SetPair implements DbCommand<Integer> {
        public int value;

        public SetPair() {
        }

        public SetPair(int value) {
            this.value = value;
        }

        @Override
        public Integer execute(WriteContext ctx, Object root) {
            TestRoot testRoot = (TestRoot) root;
            ctx.edit(testRoot);
            testRoot.a = value;
            testRoot.b = value;
            return value;
        }
    }

    public static final class Boom implements DbCommand<String> {
        @Override
        public String execute(WriteContext ctx, Object root) {
            TestRoot testRoot = (TestRoot) root;
            ctx.edit(testRoot.entries);
            testRoot.entries.put("doomed", "x");
            throw new IllegalStateException("command failed on purpose");
        }
    }

    private Commands() {
    }
}
