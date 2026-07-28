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

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Child-JVM probe for schema-evolution tests. It works entirely by reflection so it can be
 * compiled once and then run against <em>different compiled versions</em> of the same domain
 * class — which is the only faithful way to test what a release upgrade or rollback does.
 * <p>
 * {@code EvolutionProbe write <storeDir> <className> <key> <field=value>...}<br>
 * {@code EvolutionProbe read  <storeDir> <className> <key> <field>...}
 * <p>
 * Prints {@code OK <field>=<value> ...} or {@code FAILED <exception>}.
 */
public final class EvolutionProbe {

    public static void main(String[] args) {
        String mode = args[0];
        Path storeDir = Path.of(args[1]);
        String className = args[2];
        String key = args[3];

        // Optional 5th token "matching=strict|lenient" selects the evolution policy.
        com.zeroz4j.db.schema.SchemaEvolution evolution =
                com.zeroz4j.db.schema.SchemaEvolution.strict();
        for (String arg : args) {
            if (arg.equals("matching=lenient")) {
                evolution = com.zeroz4j.db.schema.SchemaEvolution.lenient();
            }
        }

        try (ZeroZDb db = ZeroZDb.open(new HashMap<String, Object>(), storeDir,
                Durability.OS_BUFFERED, evolution)) {
            Map<String, Object> root = db.root();

            if (mode.equals("write")) {
                Object instance = Class.forName(className).getDeclaredConstructor().newInstance();
                for (int i = 4; i < args.length; i++) {
                    if (args[i].startsWith("matching=")) {
                        continue;
                    }
                    int split = args[i].indexOf('=');
                    set(instance, args[i].substring(0, split), args[i].substring(split + 1));
                }
                db.write(ctx -> {
                    ctx.edit(root);
                    root.put(key, instance);
                });
                System.out.println("OK written");
            } else {
                Object instance = root.get(key);
                if (instance == null) {
                    System.out.println("FAILED no object under key " + key);
                    System.exit(1);
                }
                StringBuilder out = new StringBuilder("OK");
                out.append(" class=").append(instance.getClass().getName());
                for (int i = 4; i < args.length; i++) {
                    if (args[i].startsWith("matching=")) {
                        continue;
                    }
                    out.append(' ').append(args[i]).append('=').append(get(instance, args[i]));
                }
                System.out.println(out);
            }
            System.out.flush();
        } catch (Throwable t) {
            System.out.println("FAILED " + t.getClass().getName() + ": " + t.getMessage());
            System.out.flush();
            System.exit(1);
        }
    }

    private static void set(Object instance, String name, String value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        if (field.getType() == int.class) {
            field.setInt(instance, Integer.parseInt(value));
        } else {
            field.set(instance, value);
        }
    }

    private static Object get(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(instance);
        } catch (NoSuchFieldException e) {
            return "<absent-in-this-version>";
        } catch (IllegalAccessException e) {
            return "<unreadable>";
        }
    }
}
