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
package com.zeroz4j.db.evolution;

import com.zeroz4j.db.Durability;
import com.zeroz4j.db.ZeroZDb;
import com.zeroz4j.db.schema.SchemaEvolution;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Child-JVM probe for enum-evolution tests. Works entirely by reflection so that one compiled
 * probe can run against <em>different compiled versions</em> of the same enum — the only faithful
 * way to reproduce "release 2 declares the constants in a different order".
 * <p>
 * {@code EnumProbe write <storeDir> <key> <constantName>}<br>
 * {@code EnumProbe read  <storeDir> <key>}<br>
 * {@code EnumProbe open  <storeDir> <key>} — opens and closes, touching nothing, to establish
 * whether a refusal happens when the store is opened or only when the value is read.
 * <p>
 * Prints {@code OK status=<name> ordinal=<n>} or {@code FAILED <exception chain>}. The whole cause
 * chain is printed because the interesting exception is usually wrapped several layers deep, and
 * the test asserts on which one it is.
 */
public final class EnumProbe {

    public static void main(String[] args) {
        String mode = args[0];
        Path storeDir = Path.of(args[1]);
        String key = args[2];

        try (ZeroZDb db = ZeroZDb.open(new HashMap<String, Object>(), storeDir,
                Durability.OS_BUFFERED, SchemaEvolution.strict())) {
            Map<String, Object> root = db.root();

            if (mode.equals("open")) {
                System.out.println("OK opened entries=" + root.size());
            } else if (mode.equals("write")) {
                Object holder = writeHolder(key, args[3]);
                db.write(ctx -> {
                    ctx.edit(root);
                    root.put(key, holder);
                });
                System.out.println("OK written status=" + args[3]);
            } else {
                Object holder = root.get(key);
                if (holder == null) {
                    System.out.println("FAILED no object under key " + key);
                    System.out.flush();
                    System.exit(1);
                }
                Enum<?> status = (Enum<?>) holder.getClass().getField("status").get(holder);
                System.out.println("OK status=" + (status == null ? "null" : status.name())
                        + " ordinal=" + (status == null ? -1 : status.ordinal()));
            }
            System.out.flush();
        } catch (Throwable t) {
            System.out.println("FAILED " + chain(t));
            System.out.flush();
            System.exit(1);
        }
    }

    private static Object writeHolder(String key, String constantName) throws Exception {
        Class<?> holderType = Class.forName(EnumVersions.HOLDER_NAME);
        Class<?> statusType = Class.forName(EnumVersions.ENUM_NAME);
        Object holder = holderType.getDeclaredConstructor().newInstance();
        holderType.getField("id").set(holder, key);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object constant = Enum.valueOf((Class) statusType, constantName);
        holderType.getField("status").set(holder, constant);
        return holder;
    }

    /** Every exception class and message down the cause chain, on one line. */
    private static String chain(Throwable t) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (!text.isEmpty()) {
                text.append(" <- ");
            }
            text.append(current.getClass().getName()).append(": ")
                    .append(String.valueOf(current.getMessage()).replace('\n', ' '));
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }

    private EnumProbe() {
    }
}
