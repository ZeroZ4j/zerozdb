# ZeroZ DB — developer guide

For someone building an application against ZeroZ DB. The
[feasibility and design document](Feasibility-And-Design.md) explains *why* it is built this way;
this explains *how to use it*.

Version 0.2.0. See [API stability](#api-stability) before depending on anything.

---

## 1. Getting it

From Maven Central:

```xml
<dependency>
    <groupId>com.zeroz4j</groupId>
    <artifactId>zerozdb</artifactId>
    <version>0.2.0</version>
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

### When a transaction is leaked

A transaction that is never committed, rolled back or closed holds the store's write lock until
the process ends. Only the thread that took it can release it, and that thread has moved on. From
that moment the store cannot be written to and cannot be closed — which matters most in a server,
where a store that cannot be closed cannot be handed back.

This is the one failure a `begin()`/`commit()` API makes possible and a write-block does not, so
the engine treats it as a first-class thing to report rather than a hang to be diagnosed later:

```java
// Assert at the end of a request that nothing was left open.
if (db.hasOpenWriteBlock()) {
    log.error("left a write block open: {}", db.describeLockState());
}
```

| call | answers |
|---|---|
| `db.hasOpenWriteBlock()` | is a write-block open on this store, by any thread |
| `db.writeBlockOwner()` | which thread opened it (the `Thread`, so you can dump its stack) |
| `db.describeLockState()` | one sentence naming the holder, for a log line |
| `db.isWriteActive()` | am *I* inside a write — a ThreadLocal, and never an answer about others |

Two more things happen without being asked. A transaction that is garbage-collected without
ending logs a warning naming the thread that opened it. And `close()` gives up after 30 seconds
rather than parking for ever, throwing `StoreBusyException` with the same information —
see [close() does not return](guides/troubleshooting.md#closing-a-store-does-not-return).

While hunting a leak, run with `-Dzerozdb.traceWriteBlockOrigins=true` (or call
`ZeroZDb.traceWriteBlockOrigins(true)`). Every message then carries the stack that opened the
block, which is the difference between knowing a block leaked and knowing which line leaked it.
It costs about a microsecond per block, so it is off by default.

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

### Enums: append only, and put the catch-all first

An enum value is persisted as its **position** in the declaration, not as its name. The names are
recorded next to it, and EclipseStore uses them on load to check that nothing moved.

The table below is proven by `EnumEvolutionTest`, which compiles two orderings of the same enum and
runs each in its own JVM — the only faithful way to reproduce a release that reorders one.

| Change to the constant list | What actually happens |
|---|---|
| Append a constant at the end | **Safe.** Every stored value keeps its position. |
| Insert one, remove one from the middle, or reorder | **The store will not open at all.** Loading fails with `PersistenceExceptionTypeConsistencyEnum`, naming the constant whose position moved. Nothing is corrupted — put the old order back and the store reads again — but the release cannot run. |
| Remove the last constant, or rename any constant | **Silent.** The store opens, every other value is fine, and every value stored as the vanished name comes back as `null`. |

Note what is *not* in that table: a stored value never comes back as a different constant. The
ordering check catches that case before it can happen.

So the rule is: **only ever append.** And because appending is the only room a release has, a
catch-all constant such as `OTHER` has to be declared **first**:

```java
public enum Source {
    OTHER,          // first, so that anything added later can simply be appended
    REFERRAL,
    SEARCH,
    CAMPAIGN        // new constants join here, at the end, for ever
}
```

Declared last, `OTHER` leaves nowhere safe to add anything: a new constant either goes after the
catch-all, which reads wrong, or in front of it, which moves the catch-all's position and stops the
store from opening.

A constant you no longer want should be deprecated rather than deleted, so stored values still
resolve:

```java
@Deprecated
CANCELLED,          // no longer offered; kept so existing data still loads
```

`SchemaCompatibility` checks all of this — but only for the enums you actually hand it, so list
your enum classes alongside your entity classes:

```java
SchemaCompatibility.check(Path.of("src/test/resources/schema-baseline.txt"),
        List.of(ShopRoot.class, Product.class, Source.class));
```

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

## 11. Collections that grow without limit: `Lazy`

A store has to fit the heap. EclipseStore's `Lazy` reference is how a *store* can be bigger than
what is resident: the holder is a small object that remembers an object id, the graph behind it is
fetched on first use, and it can be released again afterwards.

```java
public class Song {
    public String title;                                   // resident: listings and indexes need it
    public Lazy<List<Note>> notes = Lazy.Reference(new ArrayList<>());

    public List<Note> notes() {
        return notes.get();                                // loads from disk on first call
    }
}
```

Reopening the store brings back the titles and not the notes. `notes.get()` fetches them,
`notes.clear()` releases them, `notes.isLoaded()` says which state it is in.

That much is ordinary EclipseStore. The rest of this section is what `Lazy` does to the guarantees
the rest of this guide makes — all of it proven by `LazyTest`, with the unproven parts marked.

The one sentence to remember: **a `Lazy` holder is a single reference-shaped field, and it never
changes.** Everything this engine does by looking at an object's own fields — enlistment,
before-images, index key extraction — therefore sees nothing at all when the collection behind the
holder changes.

### Enlist what is behind the reference, never the owner

```java
db.write(ctx -> {
    song.notes().add(note);
    ctx.store(song);            // WRONG: song's own record did not change
});

db.write(ctx -> {
    song.notes().add(note);
    ctx.store(song.notes());    // right: enlist the materialised collection
});
```

This is the ordinary "enlisting does not cascade" rule, but `Lazy` makes it easier to get wrong,
because `ctx.store(song)` *looks* like it covers everything reachable from the song.

### Rollback does not reach behind a `Lazy`

A before-image of an object records its flat field values. The `Lazy` field holds the same holder
object before and after, so a failed write-block restores nothing and the mutation survives in
memory:

```java
db.write(ctx -> {
    ctx.edit(song);             // snapshots the holder, not the notes
    song.notes().add(note);
    throw new RuntimeException("boom");
});                             // the note is still in the list
```

Enlisting the materialised collection restores normal fidelity, because a Map or Collection
before-image snapshots contents:

```java
ctx.edit(song.notes());         // now rollback puts the list back
```

So `ctx.edit(lazy.get())` is the habit to build, for the same call that `ctx.store(lazy.get())`
will end.

### Indexes

An index needs something to iterate, and a holder is neither a Map nor a Collection, so a holder
cannot be an index source — `db.index(..., () -> song.notes, ...)` throws `IllegalStateException`.

Pointing an index at the materialised collection works, and costs exactly what the `Lazy` was
avoiding: registering the index scans the source, so the collection is in memory from then on, and
every commit that enlists it scans it again.

The shape that keeps both properties is to **index the owners, not their contents**:

```java
Index<String, Song> byTitle = db.index("by-title", Song.class, () -> root.songs, s -> s.title);
```

The songs are small and resident; each song's notes stay on disk until somebody opens that song.

### Eviction is already running — you probably do not need your own

EclipseStore starts its global `LazyReferenceManager` when the storage manager starts, so a
background thread is already clearing untouched references under this engine with no configuration
at all. `LazyTest` asserts it is running after `ZeroZDb.open(...)`; the application never calls
`start()`.

Its defaults, read from EclipseStore 4.2.0's source rather than measured here: it wakes every
second with a one-millisecond budget, and clears a reference that has not been touched for about
sixteen minutes, sooner under memory pressure. That is a slow, safe background trickle, not prompt
eviction.

Clearing by hand is safe and reversible — `lazy.clear()` releases the collection and the next
`get()` loads it again — with one trap worth knowing if you plan to clear on some event of your
own, such as a user logging out: clearing a holder whose contents have **never been committed**
throws `IllegalStateException`, because there would be nowhere to load them back from. That is the
library protecting unsaved work, not a bug.

### Inside read blocks and write blocks

Loading a reference is disk I/O, and it works inside both kinds of block. Inside `db.read(...)`
that is unremarkable.

Inside a write-block it extends how long the write lock is held, by a disk read, while every other
writer waits. The guidance is therefore to **materialise before the block**:

```java
List<Note> notes = song.notes();        // load outside
db.write(ctx -> {
    notes.add(note);
    ctx.store(notes);
});
```

Honest caveat: that this hurts under contention is reasoning, not measurement. Nothing here has
measured how much a `Lazy` load inside a write-block costs concurrent readers.

### `Lazy` cannot cross the wire

This is the hard limit. The network layer's wire format is EclipseStore's standalone serializer,
which has no storage behind it and so cannot write a reference whose entire meaning is "ask the
storage for object 12345". It refuses explicitly:
`UnsupportedOperationException: Lazy references cannot be serialized`.

That applies whether or not the owner has the reference loaded, and it means:

- a `ReplicaView` of a graph containing a `Lazy` cannot be built;
- a `DbQuery` must not return objects that hold `Lazy` fields.

It fails badly, too: the reply is serialized outside the server's per-request error handling, so
the connection drops and the caller sees an `EOFException` rather than anything naming a `Lazy`.
Have queries return the values the client needs — plain lists, records of primitives — rather than
your stored entities.

**`Lazy` is therefore an embedded-mode technique.** It is the right answer to a large single-JVM
store, and it is not available to the replicated deployments described in §8. The design document's
claim that a replica materialises only what it navigates does not hold for `Lazy`.

## API stability

**0.x — the API may change.** What is settled by real use: `ZeroZDb` open/attach/read/write,
`WriteContext`, `WriteTransaction`, `Durability`, `Index`/`UniqueIndex`, `CrossStoreWrite`.
Newer and more likely to move: `ZeroZDbNode`, `ReplicaView`, `ConsoleServer`, the lease arbiters,
`SchemaPolicy`/`StoreSchema`.

Anything not mentioned in this guide should be treated as internal even when it is `public`.

## Known limits

- **Everything in a store must fit the heap.** `Lazy` references mitigate it in embedded mode —
  see §11 for what they cost and the fact that they cannot be replicated. Very large datasets are
  the wrong fit — see the design document's §3.
- **Enum constants can only be appended.** Any other edit either stops the store from opening or
  turns stored values into `null` — see §9.
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
