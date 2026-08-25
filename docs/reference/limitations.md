# Limitations

What ZeroZ DB is not for, and what it does not do yet. Stated plainly so a decision to use it is an
informed one.

## Shape of the data

- **A store must fit the heap.** The object graph *is* the heap — that is the whole reason this is
  fast. EclipseStore `Lazy` references keep the resident footprint proportional to what you
  actually navigate, but a dataset that fundamentally exceeds memory is the wrong fit. At that
  point the honest answer is PostgreSQL.
- **No query language.** Queries are Java: streams over the graph, plus maintained indexes for
  lookups that would otherwise scan. This is deliberate, not missing. If you need ad-hoc analytical
  queries over large data, this is the wrong tool.

## Concurrency and scale

- **One writer per store.** Writes are serialized, which is what makes them safe without locks in
  application code. Measured on a dev laptop: ~280 commits/s with fsync, ~2.4k without. Stores are
  independent, so N stores give N concurrent writers.
- **No store eviction.** A process that opens many stores lazily and never closes them holds them
  all. An LRU registry is designed but unbuilt; until then, close stores you no longer need.
- **Reads are not isolated from writes in every consumer.** The engine's own `db.read(...)` blocks
  are, but a host framework that reads the graph directly without going through the engine is not.
- **A leaked write-block cannot be recovered, only reported.** A `beginWrite()` transaction that is
  never ended holds the write lock until the process exits, because only the thread that took it
  can release it. The engine says so loudly — `close()` fails with `StoreBusyException` naming that
  thread, a dropped transaction logs a warning — but it cannot take the lock back, and there is no
  way to force a store closed underneath a writer without risking its files.

## Multiple JVMs

- **Never two writers on one store.** Everything funnels to one owner per store. This is a
  guarantee, not a limitation to be worked around — two JVMs mutating one live object graph cannot
  be made safe.
- **Replication ships whole snapshots.** A replica refreshes by fetching the graph, so cost is
  O(graph) per change batch, not O(change). Right for read-heavy stores with modest write rates;
  wrong for a large graph under constant writes. Incremental diff shipping is designed but unbuilt.
- **Replicas are stale by up to one refresh.** Typically one round trip. Use `node.query(...)` when
  a read must be exactly current.
- **Cross-store writes are not distributed 2PC.** `CrossStoreWrite` coordinates stores *this JVM
  owns*, validating all participants before committing any. Process death between two participants'
  commits leaves the earlier ones committed. A store served by another JVM cannot participate at
  all.
- **Cross-host ownership needs a lease.** The default file-lock arbiter is correct on local disks
  and RWO volumes, not on NFS or SMB. `LeaseFileArbiter` covers those, with one residual window: a
  process frozen beyond its lease could in principle resume mid-write. Closing that needs
  storage-level fencing EclipseStore does not offer.

## Security

- **One shared secret per server.** No per-client identity, no per-store authorisation. A client
  that can connect can use every store the server exposes.
- **No audit log** of who executed which command.
- **The console is read-only** and has no user accounts — one password, and it can read every value
  in the stores it is given.

## Operations

- **No metrics or health endpoint** beyond the console's overview page.
- **No connection limits or backpressure** on the server.
- **The server is not generic middleware.** It loads your domain classes, because it executes your
  commands and maintains your indexes, so it is deployed and versioned with your application jar
  rather than installed independently.

## Schema

- **Rolling upgrades work only for additive changes.** Adding fields keeps the previous release
  able to read the data; removing or retyping them does not, from the moment the new release
  rewrites a record. `SchemaCompatibility` enforces this at build time rather than leaving it to
  discipline.
- **An enum's constants can only ever be appended.** An enum value is persisted as its *position*
  in the declaration. Inserting a constant, removing one from the middle, or reordering them stops
  the store from opening at all; removing the last constant, or renaming any constant, is worse
  because it is silent — the store opens and every value stored as the vanished name loads back as
  `null`. There is no in-place fix for an enum whose ordering has already shipped wrong, only
  putting the old order back. Practical consequence: a catch-all constant such as `OTHER` must be
  declared **first**, because appending is the only edit a later release can make. See the guide's
  §9; proven by `EnumEvolutionTest`.
- **Records cannot cross the wire** without `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`
  on the JVM. Use plain classes for commands, queries and anything else serialized.

## Maturity

Version 0.2.0. Proven against one real consumer application and covered by 126 tests, including
multi-process harnesses that kill JVMs and verify invariants across process boundaries — but it has
not run in production anywhere, and it has never been benchmarked beyond a development machine.

The API may move in 0.x. Settled by real use: `ZeroZDb`, `WriteContext`, `WriteTransaction`,
`Durability`, `Index`/`UniqueIndex`, `CrossStoreWrite`. More likely to change: `ZeroZDbNode`,
`ReplicaView`, `ConsoleServer`, the lease arbiters, `SchemaPolicy` and `StoreSchema`.
