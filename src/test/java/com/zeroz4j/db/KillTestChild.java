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

import java.nio.file.Path;

/**
 * Child process for {@link DurabilityKillTest}: writes entries forever, printing "ACK n" only
 * after {@code write} has returned (i.e. after the commit claims durability). The parent
 * hard-kills it mid-run and verifies every acked entry survived.
 */
public final class KillTestChild {

    public static void main(String[] args) {
        Path dir = Path.of(args[0]);
        try (ZeroZDb db = ZeroZDb.open(new TestRoot(), dir)) {
            TestRoot root = db.root();
            for (int i = 0; ; i++) {
                int n = i;
                db.write(ctx -> {
                    root.entries.put("k" + n, "v" + n);
                    ctx.store(root.entries);
                });
                System.out.println("ACK " + n);
                System.out.flush();
            }
        }
    }
}
