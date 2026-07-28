# ZeroZ DB

The zero-impedance Java database: your objects are the database. A pure-Java engine over
[EclipseStore](https://eclipsestore.io) that adds what a real database needs — serialized
atomic transactions, crash durability, concurrent readers, ownership safety across JVMs —
without ever leaving plain Java objects.

Part of the ZeroZ4J family (zeroz4j.com).

```java
ZeroZDb db = ZeroZDb.open(new MyRoot(), Path.of("data/mystore"));
MyRoot root = db.root();

db.write(ctx -> {                       // serialized, atomic, durable on return
    root.prospects().put(id, prospect); // plain Java mutation — the EclipseStore way
    ctx.store(root.prospects());
});

List<Prospect> hot = db.read(() ->      // concurrent, never sees torn state
    root.prospects().values().stream().filter(Prospect::isHot).toList());
```

**Start here: [docs/Guide.md](docs/Guide.md)** — how to build against it, with the API, the
patterns, the API-stability statement and the known limits.

Also: [CHANGELOG.md](CHANGELOG.md) · [design journal and rationale](docs/Feasibility-And-Design.md).

Status: **0.1.0-SNAPSHOT, not yet published to a repository** — `mvn install` locally to depend
on it. 104 tests. Proven against one real consumer application; the API may still move in 0.x.

## Build

```
mvn test
```

Requires JDK 21+. The test suite includes a kill-the-JVM-mid-write durability proof
(`DurabilityKillTest`).

## Three modes, one API

```java
// Private to this JVM: no socket, one copy of the graph, full transactions.
ZeroZDbNode tenant = ZeroZDbNode.embedded(Path.of("data/tenant-42"), TenantRoot::new);

// Shared: owns and serves it if free, joins the owner if not.
ZeroZDbNode shared = ZeroZDbNode.open(Path.of("data/root"), RootSegment::new);

// Never owns data — for app JVMs in front of a dedicated server.
ZeroZDbNode app = ZeroZDbNode.builder(Path.of("data/root"), RootSegment::new)
        .mode(ZeroZDbNode.Mode.CLIENT_ONLY).build();
```

`execute`, `query` and `localReads` behave identically in every mode, so application code is
written once. `localDb()` — lambda write-blocks, index registration, `CrossStoreWrite` — is
available wherever the node owns its data.

## Standalone server

```bash
java -cp zerozdb.jar:my-domain.jar com.zeroz4j.db.server.ZeroZDbServerMain server.properties
```

```properties
port = 5150
schemaId = myapp-v3
durability = SYNC
store.shop.dir  = /data/shop
store.shop.root = com.example.ShopRoot
```

The daemon publishes an endpoint beside each store, so `CLIENT_ONLY` nodes find it without any
port configuration. It stops cleanly on SIGTERM (`docker stop`) and on stdin EOF — the latter
because Windows has no SIGTERM and never runs shutdown hooks.

## Cross-host ownership (containers, shared volumes)

```java
ZeroZDbNode.builder(dir, Root::new)
        .arbiter(new LeaseFileArbiter())      // instead of the default OS file lock
        .ownerId("pod-7")
        .build();
```

File locks are unreliable on NFS/SMB. `LeaseFileArbiter` uses a heartbeat-renewed lease file
with a fencing epoch: a challenger takes over only after expiry plus grace, and the displaced
owner stops serving within one heartbeat. See
[docs/Feasibility-And-Design.md](docs/Feasibility-And-Design.md) §6.8 for the residual
frozen-process window and why one RWO volume per store is still the safest arrangement.

## Auto-server mode — point JVMs at a store, stop worrying

```java
// Every JVM runs exactly this. The first one owns the store and serves it;
// the rest discover it and become clients. Same code, same API, either role.
ZeroZDbNode node = ZeroZDbNode.open(Path.of("data/shop"), ShopRoot::new);

long id = node.execute(new AddProduct("SKU-1", "Laptop stand"));
int count = node.query(new ProductCount());
```

If the owner JVM dies, a surviving node reconnects to whoever took over — or promotes itself
and carries on. The in-flight call is retried, so callers see a slow call rather than an error,
and no acknowledged write is lost (proven by `FailoverStress`, which hard-kills the owner).

For a dedicated-server deployment where app JVMs must never own data, build with
`.allowPromotion(false)`.

### Reads at heap speed, even on a client

```java
try (var local = node.localReads()) {              // live graph if owner, replica if client
    int hot = local.read(root -> root.prospects().size());   // 130 ns, no round trip
}
```

A client's replica is refreshed the instant the owner commits (a long poll, not polling), and
snapshots swap atomically so a reader never sees a half-applied change. Measured: **130 ns per
local read vs 368 µs for the same read as a remote query — 2835×.** The trade is staleness
bounded by one refresh; when a read must be current, use `node.query(...)`, which always runs on
the owner. Refresh currently ships a whole snapshot, so this suits read-heavy stores with modest
write rates — incremental diffs are future work.

## Client-server mode (explicit)

Other JVMs never touch the store files — they talk to a server that owns them:

```java
// Server JVM
ZeroZDb db = ZeroZDb.open(new ShopRoot(), Path.of("data/shop"));
ZeroZDbServer server = ZeroZDbServer.builder()
        .store("shop", db).schemaId("v1").port(5150).start();

// Client JVM — commands run on the server, inside one atomic durable write-block
ZeroZDbClient client = ZeroZDbClient.connect("127.0.0.1", 5150, "v1");
long id = client.execute("shop", new AddProduct("SKU-1", "Laptop stand"));
int count = client.query("shop", new ProductCount());
```

Writes travel as `DbCommand` objects and reads as `DbQuery` objects — both plain classes from
your domain jar, serialized with EclipseStore's own serializer. A client whose `schemaId`
differs is refused at connect rather than allowed to silently drop fields it doesn't know.
Server-side failures arrive as the same exception types you'd catch embedded.

## Example client

A complete consumer lives in
[src/test/java/org/zeroz4j/db/example](src/test/java/org/zeroz4j/db/example) — a product
catalog service (`ProductService` over `ShopRoot`) showing the intended patterns: id allocation
and insertion in one atomic block, unique SKU constraint aborting the whole commit (counter
included), category index maintenance, and reopen. `ExampleClientTest` walks the full session
and runs with the suite.

## Stress application

`src/test/java/org/zeroz4j/db/stress/` runs N Loom virtual-thread clients against a live engine
with invariant-checked workloads (bank transfers, contended optimistic counter, unique-index
churn, cross-store writes), then reopens the store and re-verifies. A short version runs with
the suite; for a soak run:

```bash
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" com.zeroz4j.db.stress.StressHarness 128 120 SYNC
```

Measured on a dev laptop with 48 clients: ~280 commits/s with fsync (`SYNC`), ~2.4k without
(`OS_BUFFERED`), ~1.6k reads/s alongside, store open ~150 ms.

`MultiJvmStress` does the same across **real processes** — one server JVM, N client JVMs over
TCP — verifying money, counter and index invariants across process boundaries and after reopen:

```bash
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt && java -cp "target/classes;target/test-classes;$(cat target/cp.txt)" com.zeroz4j.db.stress.MultiJvmStress 6 16 30 SYNC
```

Measured: 3 client JVMs × 8 threads reached 3566 remote writes/s and 1456 reads/s
(`OS_BUFFERED`); remote throughput tracks embedded throughput, so the wire is not the
bottleneck — the single writer and fsync are. See
[docs/Feasibility-And-Design.md](docs/Feasibility-And-Design.md) §10.

## Durability note

Plain EclipseStore never calls `fsync` — verified against its 4.1.0 sources — so native commits
survive process death but not power loss. ZeroZ DB defaults to `Durability.SYNC`, which forces
the channel after every storage write; pass `Durability.OS_BUFFERED` to `open(...)` for
EclipseStore's native (faster, weaker) behavior during bulk loads.
