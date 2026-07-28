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
package com.zeroz4j.db.console;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders one node of a live object graph as JSON: its own scalar values inline, and its
 * references as navigable links rather than expanded subtrees.
 * <p>
 * Domain-aware on purpose. EclipseStore's own REST browser walks raw object ids; this walks
 * <em>your field names</em>, which is what an operator answering "what does this customer look
 * like?" actually needs. One level at a time keeps the response bounded no matter how large or
 * cyclic the graph is.
 */
final class GraphBrowser {

    static final int DEFAULT_PAGE = 50;

    /**
     * Navigates from {@code root} along a slash-separated path of field names, map keys and
     * list indices, then renders the node found there.
     */
    static String render(Object root, String path, int offset, int limit) {
        Object node = navigate(root, path);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path == null ? "" : path);
        out.put("type", node == null ? null : node.getClass().getName());

        if (node == null || isScalar(node)) {
            out.put("kind", "value");
            out.put("value", node == null ? null : String.valueOf(node));
            return Json.value(out);
        }
        if (node instanceof Map<?, ?> map) {
            out.put("kind", "map");
            out.put("size", map.size());
            List<Object> entries = new ArrayList<>();
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (index++ < offset) {
                    continue;
                }
                if (entries.size() >= limit) {
                    break;
                }
                Map<String, Object> rendered = new LinkedHashMap<>();
                rendered.put("key", String.valueOf(entry.getKey()));
                describe(rendered, entry.getValue(), join(path, String.valueOf(entry.getKey())));
                entries.add(rendered);
            }
            out.put("entries", entries);
            return Json.value(out);
        }
        if (node instanceof Collection<?> collection) {
            out.put("kind", "collection");
            out.put("size", collection.size());
            List<Object> elements = new ArrayList<>();
            int index = 0;
            for (Object element : collection) {
                int position = index++;
                if (position < offset) {
                    continue;
                }
                if (elements.size() >= limit) {
                    break;
                }
                Map<String, Object> rendered = new LinkedHashMap<>();
                rendered.put("index", position);
                describe(rendered, element, join(path, String.valueOf(position)));
                elements.add(rendered);
            }
            out.put("elements", elements);
            return Json.value(out);
        }
        if (node.getClass().isArray()) {
            out.put("kind", "array");
            int length = Array.getLength(node);
            out.put("size", length);
            List<Object> elements = new ArrayList<>();
            for (int i = offset; i < length && elements.size() < limit; i++) {
                Map<String, Object> rendered = new LinkedHashMap<>();
                rendered.put("index", i);
                describe(rendered, Array.get(node, i), join(path, String.valueOf(i)));
                elements.add(rendered);
            }
            out.put("elements", elements);
            return Json.value(out);
        }

        out.put("kind", "object");
        List<Object> fields = new ArrayList<>();
        for (Field field : fieldsOf(node.getClass())) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("name", field.getName());
            try {
                field.setAccessible(true);
                describe(rendered, field.get(node), join(path, field.getName()));
            } catch (ReflectiveOperationException | RuntimeException e) {
                rendered.put("kind", "error");
                rendered.put("value", e.getClass().getSimpleName());
            }
            fields.add(rendered);
        }
        out.put("fields", fields);
        return Json.value(out);
    }

    private static void describe(Map<String, Object> out, Object value, String path) {
        if (value == null) {
            out.put("kind", "value");
            out.put("value", null);
            return;
        }
        out.put("type", value.getClass().getName());
        if (isScalar(value)) {
            out.put("kind", "value");
            out.put("value", String.valueOf(value));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.put("kind", "map");
            out.put("size", map.size());
        } else if (value instanceof Collection<?> collection) {
            out.put("kind", "collection");
            out.put("size", collection.size());
        } else if (value.getClass().isArray()) {
            out.put("kind", "array");
            out.put("size", Array.getLength(value));
        } else {
            out.put("kind", "object");
        }
        out.put("path", path);        // the link an operator follows
    }

    private static Object navigate(Object root, String path) {
        Object current = root;
        if (path == null || path.isBlank()) {
            return current;
        }
        for (String step : path.split("/")) {
            if (step.isEmpty()) {
                continue;
            }
            current = stepInto(current, step);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Object stepInto(Object node, String step) {
        if (node == null) {
            return null;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (step.equals(String.valueOf(entry.getKey()))) {
                    return entry.getValue();
                }
            }
            return null;
        }
        if (node instanceof List<?> list) {
            int index = Integer.parseInt(step);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        if (node instanceof Collection<?> collection) {
            int index = Integer.parseInt(step);
            int i = 0;
            for (Object element : collection) {
                if (i++ == index) {
                    return element;
                }
            }
            return null;
        }
        if (node.getClass().isArray()) {
            int index = Integer.parseInt(step);
            return index >= 0 && index < Array.getLength(node) ? Array.get(node, index) : null;
        }
        for (Field field : fieldsOf(node.getClass())) {
            if (field.getName().equals(step)) {
                try {
                    field.setAccessible(true);
                    return field.get(node);
                } catch (ReflectiveOperationException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>
                || value instanceof java.time.temporal.Temporal || value instanceof java.util.Date
                || value.getClass().isPrimitive();
    }

    private static String join(String path, String step) {
        return path == null || path.isBlank() ? step : path + "/" + step;
    }

    private GraphBrowser() {
    }
}
