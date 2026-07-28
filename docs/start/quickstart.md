# Quickstart

The smallest working ZeroZ DB program, and the three rules that matter.

## 1. Depend on it

Not published to a repository yet, so build it once:

```bash
git clone https://github.com/zeroz4j/zerozdb.git
cd zerozdb && mvn install
```

```xml
<dependency>
    <groupId>com.zeroz4j</groupId>
    <artifactId>zerozdb</artifactId>
    <version>0.1.0</version>
</dependency>
```

JDK 21 or later.

## 2. Define a root

Your database is a graph of plain objects hanging off one root. No annotations, no interfaces, no
schema.

```java
public class ShopRoot {
    public final Map<String, Product> products = new HashMap<>();
    public long nextId = 1;
}

public class Product {
    public long id;
    public String sku;
    public String name;

    public Product() { }
    public Product(long id, String sku, String name) {
        this.id = id; this.sku = sku; this.name = name;
    }
}
```

## 3. Open, write, read

```java
try (ZeroZDb db = ZeroZDb.open(new ShopRoot(), Path.of("data/shop"))) {
    ShopRoot root = db.root();

    long id = db.writeResult(ctx -> {
        ctx.edit(root);                  // enlist BEFORE mutating
        ctx.edit(root.products);
        long newId = root.nextId++;
        root.products.put("SKU-1", new Product(newId, "SKU-1", "Laptop stand"));
        return newId;
    });

    int count = db.read(() -> root.products.size());
    System.out.println("stored " + count + " product(s), first id " + id);
}
```

That write is atomic and on disk when it returns. A crash cannot persist the product without the
counter that named it.

## The three rules

**Enlisting does not cascade.** `ctx.store(root)` covers the root object only — not the map hanging
off it. Changing both means enlisting both. This is the mistake everyone makes once.

**Enlist before you mutate.** `ctx.edit(obj)` takes the rollback snapshot at that moment, so a
snapshot taken after the change already contains the change. `ctx.store(obj)` does the same
enlistment and is the right call when you have already changed the object.

**Never write inside a read.** `db.read(...)` takes a shared lock, and a read lock cannot be
upgraded. The engine throws rather than deadlocking, but the fix is always to move the write out.

## Next

- [Developer guide](../Guide.md) — indexes, constraints, concurrent edits, multiple stores,
  multiple JVMs, the server, schema changes.
- [Troubleshooting](../guides/troubleshooting.md) — the failures that are silent or misleading.
- [Limitations](../reference/limitations.md) — what this database is not for.
