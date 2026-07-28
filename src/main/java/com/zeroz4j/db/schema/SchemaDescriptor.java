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
package com.zeroz4j.db.schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The persistent shape of a set of classes: which fields exist, and of what type. Capture it
 * for a release, commit the file, and compare later releases against it — see
 * {@link SchemaCompatibility}.
 * <p>
 * This is deliberately reflective and dependency-free: the shape that matters to storage is the
 * instance fields, exactly as EclipseStore sees them.
 */
public final class SchemaDescriptor {

    private final Map<String, List<FieldShape>> classes;

    public record FieldShape(String name, String type) {
        @Override
        public String toString() {
            return name + ":" + type;
        }
    }

    private SchemaDescriptor(Map<String, List<FieldShape>> classes) {
        this.classes = classes;
    }

    public static SchemaDescriptor of(Collection<Class<?>> types) {
        Map<String, List<FieldShape>> classes = new TreeMap<>();
        for (Class<?> type : types) {
            List<FieldShape> fields = new ArrayList<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                        continue;
                    }
                    fields.add(new FieldShape(field.getName(), field.getType().getName()));
                }
            }
            fields.sort(java.util.Comparator.comparing(FieldShape::name));
            classes.put(type.getName(), List.copyOf(fields));
        }
        return new SchemaDescriptor(classes);
    }

    public Map<String, List<FieldShape>> classes() {
        return classes;
    }

    /** Text form, one line per field: {@code <class> <field> <type>}. Stable and diffable. */
    public String toText() {
        StringBuilder text = new StringBuilder("# zeroz4j-db schema descriptor v1\n");
        classes.forEach((className, fields) -> {
            if (fields.isEmpty()) {
                text.append(className).append('\n');
            }
            fields.forEach(field -> text.append(className).append(' ')
                    .append(field.name()).append(' ').append(field.type()).append('\n'));
        });
        return text.toString();
    }

    public static SchemaDescriptor parse(String text) {
        Map<String, List<FieldShape>> classes = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            List<FieldShape> fields = classes.computeIfAbsent(parts[0], k -> new ArrayList<>());
            if (parts.length >= 3) {
                fields.add(new FieldShape(parts[1], parts[2]));
            }
        }
        Map<String, List<FieldShape>> immutable = new TreeMap<>();
        classes.forEach((name, fields) -> immutable.put(name, List.copyOf(fields)));
        return new SchemaDescriptor(immutable);
    }

    public void write(Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, toText());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write schema descriptor " + file, e);
        }
    }

    public static SchemaDescriptor read(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read schema descriptor " + file, e);
        }
    }
}
