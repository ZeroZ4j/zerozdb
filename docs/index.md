# ZeroZ DB

**Your objects are the database.**

A pure-Java database over [EclipseStore](https://eclipsestore.io). A graph of plain Java objects
hangs off a root object, EclipseStore persists it, and ZeroZ DB adds what a real database needs —
without a mapping layer, a query language, or SQL anywhere.

```java
ZeroZDb db = ZeroZDb.open(new ShopRoot(), Path.of("data/shop"));
ShopRoot root = db.root();

db.write(ctx -> {                          // atomic, and on disk when this returns
    root.products.put("SKU-1", new Product("Laptop stand"));
    ctx.store(root.products);
});

List<Product> all = db.read(() ->          // concurrent, never sees a half-applied write
        List.copyOf(root.products.values()));
```

## What it adds to EclipseStore

- **Transactions** — everything in a write-block commits atomically or not at all.
- **Durability that survives power loss** — including the `fsync` EclipseStore does not perform
  itself.
- **Rollback without I/O** — a failed block restores memory from before-images.
- **Indexes and unique constraints** — maintained at every commit, violations rejected before
  anything persists.
- **Conflict detection** — for the case a lock cannot help: a user editing a form while someone
  else saves.
- **Cross-store writes** — several stores changed together, with deadlock-free lock ordering.
- **Multiple JVMs** — a network server, auto-discovery, self-promoting failover, and clients that
  read their own replica at heap speed.
- **Schema safety** — strict field matching by default, and a build-time gate that fails a release
  which would break rollback.

## Where to start

<div class="grid cards" markdown>

- **[Quickstart](start/quickstart.md)** — the smallest working program, and the three rules that
  matter.
- **[Developer guide](Guide.md)** — writing, reading, indexes, concurrent edits, several stores,
  several JVMs, the server, schema changes, the console.
- **[Troubleshooting](guides/troubleshooting.md)** — the failures that are silent, or that say
  something other than what they mean.
- **[Limitations](reference/limitations.md)** — what this database is *not* for, stated plainly
  enough to decide against it.

</div>

## Is this the right tool?

**Yes, if** your data fits in memory, your workload is read-heavy with modest write rates, and you
would rather write Java than maintain a mapping layer. That describes most line-of-business
applications.

**No, if** you need ad-hoc analytical queries over large datasets, several machines writing the
same data concurrently, or a dataset that fundamentally exceeds a heap. Those are what PostgreSQL
is for, and [the design document](Feasibility-And-Design.md) explains why building around that
boundary would give you a worse Postgres rather than a better ZeroZ DB.

## Status

Version 0.1.0, 107 tests, proven against one real consumer application. The API may still move in
0.x — the [guide](Guide.md) says which parts are settled and which are not. Available from Maven
Central as `com.zeroz4j:zerozdb:0.1.0`.

Part of the **ZeroZ4J** family alongside [ZeroZ Stack](https://github.com/ZeroZ4j/zerozstack),
the pure-Java full-stack framework.
