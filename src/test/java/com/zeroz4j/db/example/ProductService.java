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

import com.zeroz4j.db.Index;
import com.zeroz4j.db.UniqueIndex;
import com.zeroz4j.db.ZeroZDb;

import java.util.List;
import java.util.Optional;

/**
 * Example application service over ZeroZ DB — the kind of class an app would inject. Reads are
 * plain object access inside read-blocks; every mutation is one atomic, durable write-block.
 */
public final class ProductService {

    private final ZeroZDb db;
    private final UniqueIndex<String, Product> bySku;
    private final Index<String, Product> byCategory;

    public ProductService(ZeroZDb db) {
        this.db = db;
        this.bySku = db.uniqueIndex("productsBySku", Product.class,
                () -> this.<ShopRoot>root().products, p -> p.sku);
        this.byCategory = db.index("productsByCategory", Product.class,
                () -> this.<ShopRoot>root().products, p -> p.category);
    }

    /**
     * Id allocation and catalog insertion in ONE commit — the two-commit crash bug from the
     * original zeroz4j example is structurally impossible here. A duplicate SKU aborts the
     * whole block: the product is not added AND the id counter bump is rolled back.
     */
    public long addProduct(String sku, String name, String category, long priceCents) {
        return db.writeResult(ctx -> {
            ShopRoot root = root();
            ctx.edit(root);
            ctx.edit(root.products);
            long id = root.nextId++;
            root.products.put(id, new Product(id, sku, name, category, priceCents));
            return id;
        });
    }

    public Optional<Product> findBySku(String sku) {
        return Optional.ofNullable(bySku.get(sku));
    }

    public List<Product> inCategory(String category) {
        return byCategory.get(category);
    }

    public void recategorize(long id, String newCategory) {
        db.write(ctx -> {
            Product product = requireProduct(id);
            ctx.edit(product);
            product.category = newCategory;
        });
    }

    public void reprice(long id, long newPriceCents) {
        db.write(ctx -> {
            Product product = requireProduct(id);
            ctx.edit(product);
            product.priceCents = newPriceCents;
        });
    }

    public boolean remove(long id) {
        return db.writeResult(ctx -> {
            ShopRoot root = root();
            ctx.edit(root.products);
            return root.products.remove(id) != null;
        });
    }

    public int productCount() {
        return db.read(() -> this.<ShopRoot>root().products.size());
    }

    public long nextId() {
        return db.read(() -> this.<ShopRoot>root().nextId);
    }

    private Product requireProduct(long id) {
        ShopRoot root = root();
        Product product = root.products.get(id);
        if (product == null) {
            throw new IllegalArgumentException("No product with id " + id);
        }
        return product;
    }

    private <T> T root() {
        return db.root();
    }
}
