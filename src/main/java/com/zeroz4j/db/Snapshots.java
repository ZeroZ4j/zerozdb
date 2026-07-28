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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Before-image capture and restore for rollback. Side tables only — persistent objects never
 * carry framework state. Maps and collections snapshot their contents (a field snapshot of the
 * owning object would only restore the reference, not the content); everything else snapshots
 * its flat fields reflectively.
 */
final class Snapshots {

    private static final Map<Class<?>, Field[]> FIELD_CACHE = new ConcurrentHashMap<>();

    static Object capture(Object object) {
        if (object instanceof Map<?, ?> map) {
            return new LinkedHashMap<>(map);
        }
        if (object instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        Field[] fields = fieldsOf(object.getClass());
        Object[] values = new Object[fields.length];
        try {
            for (int i = 0; i < fields.length; i++) {
                values[i] = fields[i].get(object);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot snapshot " + object.getClass().getName(), e);
        }
        return values;
    }

    static void restore(Object object, Object snapshot) {
        if (object instanceof Map<?, ?> && snapshot instanceof Map<?, ?> saved) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> map = (Map<Object, Object>) object;
            map.clear();
            map.putAll(saved);
            return;
        }
        if (object instanceof Collection<?> && snapshot instanceof Collection<?> saved) {
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) object;
            collection.clear();
            collection.addAll(saved);
            return;
        }
        Field[] fields = fieldsOf(object.getClass());
        Object[] values = (Object[]) snapshot;
        try {
            for (int i = 0; i < fields.length; i++) {
                Object current = fields[i].get(object);
                if (!Objects.equals(current, values[i])) {
                    fields[i].set(object, values[i]);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot restore " + object.getClass().getName(), e);
        }
    }

    private static Field[] fieldsOf(Class<?> type) {
        return FIELD_CACHE.computeIfAbsent(type, t -> {
            ArrayList<Field> fields = new ArrayList<>();
            for (Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        fields.add(f);
                    }
                }
            }
            return fields.toArray(Field[]::new);
        });
    }

    private Snapshots() {
    }
}
