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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexTest {

    private static Index<String, Person> cityIndex(ZeroZDb db) {
        return db.index("byCity", Person.class,
                () -> ((PeopleRoot) db.root()).people, p -> p.city);
    }

    @Test
    void rebuildOnRegistrationIndexesExistingData() {
        Path dir = TestStores.newDir("idx-rebuild");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            db.write(ctx -> {
                root.people.put("p1", new Person("Alice", "Berlin", "a@x.de"));
                root.people.put("p2", new Person("Bob", "Berlin", "b@x.de"));
                root.people.put("p3", new Person("Cara", "Hamburg", "c@x.de"));
                ctx.store(root.people);
            });
        }
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            Index<String, Person> byCity = cityIndex(db);
            assertEquals(2, byCity.get("Berlin").size());
            assertEquals(1, byCity.get("Hamburg").size());
            assertEquals(3, byCity.size());
        }
    }

    @Test
    void additionsAndRemovalsMaintainIndexViaCollectionDiff() {
        Path dir = TestStores.newDir("idx-diff");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            Index<String, Person> byCity = cityIndex(db);

            db.write(ctx -> {
                root.people.put("p1", new Person("Alice", "Berlin", "a@x.de"));
                ctx.store(root.people);
            });
            assertEquals(1, byCity.get("Berlin").size());

            db.write(ctx -> {
                root.people.remove("p1");
                ctx.store(root.people);
            });
            assertTrue(byCity.get("Berlin").isEmpty());
            assertEquals(0, byCity.size());
        }
    }

    @Test
    void keyChangeMovesEntry() {
        Path dir = TestStores.newDir("idx-move");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            Index<String, Person> byCity = cityIndex(db);
            db.write(ctx -> {
                root.people.put("p1", new Person("Alice", "Berlin", "a@x.de"));
                ctx.store(root.people);
            });
            Person alice = root.people.get("p1");

            db.write(ctx -> {
                ctx.edit(alice);
                alice.city = "Hamburg";
            });
            assertTrue(byCity.get("Berlin").isEmpty());
            List<Person> hamburg = byCity.get("Hamburg");
            assertEquals(1, hamburg.size());
            assertTrue(hamburg.get(0) == alice, "index must hold the live instance");
        }
    }

    @Test
    void failedBlockLeavesIndexUntouched() {
        Path dir = TestStores.newDir("idx-abort");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            Index<String, Person> byCity = cityIndex(db);
            assertThrows(RuntimeException.class, () -> db.write(ctx -> {
                ctx.edit(root.people);
                root.people.put("p1", new Person("Alice", "Berlin", "a@x.de"));
                throw new RuntimeException("boom");
            }));
            assertTrue(byCity.get("Berlin").isEmpty());
            assertTrue(root.people.isEmpty(), "rollback must restore the map");
        }
    }

    @Test
    void uniqueViolationAbortsCommitEntirely() {
        Path dir = TestStores.newDir("idx-unique");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            UniqueIndex<String, Person> byEmail = db.uniqueIndex("byEmail", Person.class,
                    () -> ((PeopleRoot) db.root()).people, p -> p.email);

            db.write(ctx -> {
                root.people.put("p1", new Person("Alice", "Berlin", "same@x.de"));
                ctx.store(root.people);
            });
            assertThrows(UniqueConstraintException.class, () -> db.write(ctx -> {
                ctx.edit(root.people);
                root.people.put("p2", new Person("Bob", "Hamburg", "same@x.de"));
            }));
            assertFalse(root.people.containsKey("p2"), "violating member rolled back in memory");
            assertEquals("Alice", byEmail.get("same@x.de").name);
        }
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            assertEquals(1, root.people.size(), "violating commit must not reach disk");
        }
    }

    @Test
    void uniqueIndexLookupAndAbsence() {
        Path dir = TestStores.newDir("idx-unique-get");
        try (ZeroZDb db = ZeroZDb.open(new PeopleRoot(), dir)) {
            PeopleRoot root = db.root();
            UniqueIndex<String, Person> byEmail = db.uniqueIndex("byEmail", Person.class,
                    () -> ((PeopleRoot) db.root()).people, p -> p.email);
            db.write(ctx -> {
                root.people.put("p1", new Person("Alice", "Berlin", "a@x.de"));
                ctx.store(root.people);
            });
            assertEquals("Alice", byEmail.get("a@x.de").name);
            assertNull(byEmail.get("nobody@x.de"));
        }
    }
}
