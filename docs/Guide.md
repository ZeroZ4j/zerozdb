# ZeroZ DB — developer guide

For someone building an application against ZeroZ DB. The
[feasibility and design document](Feasibility-And-Design.md) explains *why* it is built this way;
this explains *how to use it*.

Version 0.1.0. See [API stability](#api-stability) before depending on anything.

---

## 1. Getting it

From Maven Central:

```xml
<dependency>
    <groupId>com.zeroz4j</groupId>
    <artifactId>zerozdb</artifactId>
    <version>0.1.0</version>
</dependency>
```

Or build from source:

```bash
git clone https://github.com/ZeroZ4j/zerozdb.git
cd zerozdb && mvn install
```

Requires **JDK 21+** (virtual threads). Brings in `org.eclipse.store:storage-embedded` and
`org.eclipse.serializer:serializer`.

## 2. The model in one minute

Your objects *are* the database. There is no mapping layer, no query language, and no separate
schema: a graph of plain Java objects hangs off a root object, EclipseStore persists it, and
ZeroZ DB adds what a database needs on top — transactions, durability, indexes, constraints,
concurrency control, and optionally a network server.

Three rules carry most of the practice:

1. **Reads are just field access.** Wrap them in `db.read(...)` when you need a consistent view.
2. **Writes go inside a write-block**, and you enlist what you changed.
3. **Enlisting is not cascading.** Storing an object does not store objects it points to — that
   is EclipseStore's rule and ZeroZ DB keeps it. Enlist every level you changed.

```java
public class ShopRoot {
    public final Map<String, Product> products = new HashMap<>();
    public long nextId = 1;
}

ZeroZDb db = ZeroZDb.open(new ShopRoot(), Path.of("data/shop"));
ShopRoot root = db.root();

db.write(ctx -> {
    long id = root.nextId++;
    root.products.put("SKU-1", new Product(id, "Laptop stand"));
    ctx.store(root);            // the counter changed
    ctx.store(root.products);   // the map changed - storing root does NOT cover it
});

List<Product> all = db.read(() -> List.copyOf(root.products.values()));
db.close();
```

## 3. Writing

### Write-blocks (preferred)

```java
db.write(ctx -> { ... });                       // returns nothing
long id = db.writeResult(ctx -> { ... ; return newId; });   // returns a value
```

Everything enlisted in the block lands in **one atomic commit**, on disk before the call
returns. If the block throws, nothing is persisted and the objects you enlisted are restored in
memory from before-images. Blocks nest: an inner block joins the outer one and there is still
exactly one commit.

Inside the block:

| call | meaning |
|---|---|
| `ctx.store(obj)` | enlist a changed object |
| `ctx.edit(obj)` | enlist *before* mutating, so rollback is exact |
| `ctx.storeAll(a, b, c)` | convenience |
| `ctx.onRollback(runnable)` | compensation for effects before-images cannot undo (e.g. removing an element from a collection) |
| `ctx.storeChecked(obj, baseline)` | enlist with a stale-edit check — §6 |

Prefer `ctx.edit(obj)` *before* changing an object when you care about rollback fidelity: a
snapshot taken after the change already contains the change.

### Transactions across method calls

For frameworks whose API is `begin()` … `commit()`:

```java
try (WriteTransaction tx = db.beginWrite()) {
    prospect.setPhone("555");
    tx.context().store(prospect);
    tx.commit();
}
```

Same guarantees as a block. Always use try-with-resources: `close()` rolls back an unfinished
transaction, so a forgotten `commit()` cannot leave the store's write lock held. Nested
`beginWrite()` joins the outer transaction; a nested `rollback()` poisons the outer commit rather
than letting it persist work that was abandoned.

### Durability

`Durability.SYNC` (default) means an acknowledged write survives power loss — ZeroZ DB adds the
`fsync` EclipseStore does not do itself. `Durability.OS_BUFFERED` is EclipseStore's native
behaviour: survives process death, not a power cut, roughly 10× faster. Use it for bulk imports
and rebuildable data.

```java
ZeroZDb db = ZeroZDb.open(root, dir, Durability.OS_BUFFERED);
```

## 4. Reading

```java
List<Product> hot = db.read(() ->
        root.products.values().stream().filter(Product::isHot).toList());
```

`db.read` takes a shared lock, so the block cannot observe a half-applied write. Many readers run
concurrently. Copy or reduce inside the block — returning a live collection and iterating it
afterwards escapes the guarantee.

**Do not start a write inside a read block.** A read lock cannot be upgraded; the engine detects
this and throws rather than hanging.

## 5. Indexes and constraints

```java
Index<String, Product> byCategory =
        db.index("byCategory", Product.class, () -> root.products, p -> p.category);
UniqueIndex<String, Product> bySku =
        db.uniqueIndex("bySku", Product.class, () -> root.products, p -> p.sku);

List<Product> office = byCategory.get("office");   // O(1), no scan
Product one = bySku.get("SKU-1");
```

Registration scans once; after that the index is maintained at every commit — additions,
removals, and key changes. A unique violation throws `UniqueConstraintException` **before
anything is persisted**, and the whole write-block rolls back. Index state only changes after a
commit succeeds, so a failed block never leaves an index lying.

## 6. Concurrent edits

Writes are serialised per store, so two write-blocks cannot interleave. The remaining hazard is
*think time* — a user opens a form, someone else saves, the first user saves over it:

```java
long baseline = db.baseline(prospect);      // when the edit begins
...
db.write(ctx -> {
    ctx.edit(prospect);
    prospect.setPhone("555");
    ctx.storeChecked(prospect, baseline);   // throws StaleObjectException if it moved
});
```

Opt-in per call site; plain `store` remains last-write-wins. Objects are version-tracked only
from their first `baseline(...)`, so code that never checks pays nothing.

## 7. Several stores at once

One `ZeroZDb` per store. To change several atomically:

```java
CrossStoreWrite.run(ctx -> {
    ctx.on(registry).edit(registryRoot.tenants);
    registryRoot.tenants.put(id, tenant);
    ctx.on(tenantStore).edit(tenantRoot.settings);
    tenantRoot.settings.put("locale", "de");
}, registry, tenantStore);
```

Locks are taken in a total order, so concurrent cross-writes cannot deadlock. Every participant
is validated before any of them commits.

**Limits.** All participants must be stores *this JVM owns* — a store served by another JVM
cannot join. And this is not distributed 2PC: process death between two participants' commits
leaves the earlier ones committed. Design so that the risky multi-store work happens locally and
any shared store is touched once, last, idempotently.

## 8. Multiple JVMs

```java
// Every JVM runs this. First one owns and serves the store; the rest become clients.
ZeroZDbNode node = ZeroZDbNode.open(Path.of("data/shop"), ShopRoot::new);
long id = node.execute(new AddProduct("SKU-1"));   // DbCommand, runs on the owner
int n = node.query(new ProductCount());            // DbQuery, runs on the owner
```

| mode | owns data | serves others | use |
|---|---|---|---|
| `EMBEDDED` | yes | no socket at all | data only this JVM touches |
| `AUTO_SERVER` (default) | if free, else client | yes when owner | shared data |
| `CLIENT_ONLY` | never | no | app JVMs in front of a dedicated server |

`execute` / `query` / `localReads` behave identically in all three, so application code is written
once. `localDb()` — write-blocks, index registration, `CrossStoreWrite` — is available wherever
the node owns its data.

Remote work travels as **command objects**, not lambdas: the executing JVM needs the code, so
`DbCommand` and `DbQuery` implementations live in a jar both sides load.

```java
public class AddProduct implements DbCommand<Long> {
    public String sku;                       // public fields, no-arg constructor

    public AddProduct() { }
    public AddProduct(String sku) { this.sku = sku; }

    public Long execute(WriteContext ctx, Object root) {
        ShopRoot shop = (ShopRoot) root;
        ctx.edit(shop); ctx.edit(shop.products);
        long id = shop.nextId++;
        shop.products.put(sku, new Product(id, sku));
        return id;
    }
}
```

**Do not use records for commands, queries or anything else that crosses the wire.**
EclipseStore's serializer reaches fields directly, and the JVM refuses that for records unless
you start it with `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`. A plain class with
public fields and a no-arg constructor works everywhere. (The failure is a runtime
`Could not obtain access to "jdk.internal.misc.Unsafe"`, not a compile error, so it surfaces the
first time a command is sent rather than when it is written.)

Reads at heap speed on a client:

```java
try (var local = node.localReads()) {
    int count = local.read(root -> ((ShopRoot) root).products.size());  // ~130 ns
}
```

A client's replica refreshes the instant the owner commits, so it trails by about one round trip.
Use `node.query(...)` when a read must be exactly current.

### Standalone server

```bash
java -cp zerozdb.jar:my-domain.jar com.zeroz4j.db.server.ZeroZDbServerMain server.properties
```

```properties
port = 5150
bindAddress = 127.0.0.1
secret = change-me
schemaId = myapp-v3
durability = SYNC
store.shop.dir  = /data/shop
store.shop.root = com.example.ShopRoot
console.port = 8090
console.password = ops
```

Binding to anything other than loopback **requires** a secret; the server refuses to start
otherwise. Add TLS with `Tls.server(keystore, password)`. Cross-host deployments should also use
`LeaseFileArbiter` for ownership, since OS file locks are unreliable on network volumes.

## 9. Schema changes

Your classes are the schema. EclipseStore maps old data onto new classes automatically, and
ZeroZ DB defaults to **strict** matching: a field whose name disappeared arrives unset rather
than being silently filled from an unrelated field of the same type. Declare renames:

```java
ZeroZDb.open(root, dir, Durability.SYNC,
        SchemaEvolution.strict().rename("com.x.Product#sku", "com.x.Product#code"));
```

Guard rollback compatibility in your build:

```java
@Test
void schemaStaysRollbackCompatible() {
    SchemaCompatibility.check(Path.of("src/test/resources/schema-baseline.txt"),
            List.of(ShopRoot.class, Product.class));
}
```

Adding fields is safe and keeps rollback possible. Removing or retyping a field does not — once
the new release rewrites a record, the previous release cannot read it.

## 10. Operations console

```java
ConsoleServer console = ConsoleServer.builder()
        .store("shop", db)
        .model(ShopRoot.class, Product.class)
        .schemaBaseline(Path.of("src/test/resources/schema-baseline.txt"))
        .queries("shop", new QueryCatalog()
                .register("by-sku", "Find a product by SKU", List.of("sku"),
                        p -> new FindBySku(p.get("sku"))))
        .password("ops")
        .port(8090)
        .start();
```

Browse the object graph by field name, run the named queries you publish, see the model diffed
against the committed baseline. Read-only, loopback-only unless given a password.

## API stability

**0.x — the API may change.** What is settled by real use: `ZeroZDb` open/attach/read/write,
`WriteContext`, `WriteTransaction`, `Durability`, `Index`/`UniqueIndex`, `CrossStoreWrite`.
Newer and more likely to move: `ZeroZDbNode`, `ReplicaView`, `ConsoleServer`, the lease arbiters,
`SchemaPolicy`/`StoreSchema`.

Anything not mentioned in this guide should be treated as internal even when it is `public`.

## Known limits

- **Everything in a store must fit the heap** (EclipseStore `Lazy` references mitigate). Very
  large datasets are the wrong fit — see the design document's §3.
- **One writer per store.** Throughput ceiling ~2.4k commits/s buffered, ~280/s synced on a dev
  laptop; stores are independent, so N stores multiply that.
- **No store eviction yet.** A process that opens thousands of stores lazily and never closes
  them will hold them all; an LRU registry is designed but unbuilt.
- **Replication ships whole snapshots**, so refresh cost is O(graph) per change batch. Right for
  read-heavy stores, wrong for a large graph under constant writes.
- **`attach(manager)` uses the caller's storage configuration** — durability and schema-evolution
  settings passed to `open(...)` do not apply, because the manager already exists.
- **Authentication is one shared secret per server.** No per-client identity or per-store
  authorisation yet.
- **No metrics or health endpoint** beyond the console's overview.
- **Records cannot cross the wire** without an `--add-exports` JVM flag — see §8.
