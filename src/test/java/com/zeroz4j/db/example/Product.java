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
package com.zeroz4j.db.example;

/** Plain object, no framework anything — the zero-impedance point. */
public class Product {
    public long id;
    public String sku;
    public String name;
    public String category;
    public long priceCents;

    public Product(long id, String sku, String name, String category, long priceCents) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.priceCents = priceCents;
    }
}
