# ZeroZ DB — design and rationale

Part of the ZeroZ4J family (zeroz4j.com — "zero impedance").
Maven: `com.zeroz4j:zerozdb`. Entry class: `ZeroZDb`. 107 tests.

This records why ZeroZ DB is built the way it is: the alternatives that were considered and
rejected, the measurements that settled arguments, and the assumptions that turned out to be
wrong. It is organised as the reasoning happened rather than as a tidy finished architecture,
because the rejections and the corrections are the part worth reading — a design document that
only describes what was built teaches nothing about why.

"The consumer" refers to the first applications built on this library; their details are not
public.

For how to *use* the library, read [Guide.md](Guide.md) instead.

Question asked: can EclipseStore (ES) be turned into a more complete database — multi-user,
multi-JVM if possible, transactions, "all the good stuff" — by bolting a layer on top, while
(a) staying pure Java everywhere and (b) keeping ES's speed?

Requirements, which this design treats as requirements:

| Decision | Choice |
|---|---|
| Topology | One JVM now; product goal : N JVMs pointed at one store self-organize safely — see §1.1 |
| Concurrency model | Single-writer semantics via write-blocks (`db.write(...)`), plain POJO mutation inside |
| Change tracking | Explicit touch (`ctx.store(obj)`) — the existing ES habit, no bytecode magic |
| Workload | Read-heavy, modest writes (tens/sec) |
| Queries | Java Streams + library-managed indexes; no query language |
| Durability | Commit = on disk; an acknowledged write survives power loss |
| Packaging | Standalone library from day one, with a real consumer driving every feature |

---

## 1. Verdict, up front

**Feasible, with a sharp boundary.** Everything single-JVM — serialized transactions, atomic
commit, rollback, crash durability, stale-edit detection, indexed queries — is not only feasible
but *cheap*, because ES already provides the two hardest parts (durable atomic storage commits and
a world-class Java serializer) and because two sibling projects had already
produced converging designs for exactly this layer. The single-JVM core is a few thousand lines,
not a research project.

The boundary is multi-JVM **shared mutable state**. A prior analysis in a sibling project
states the obstacle precisely, and it is unavoidable:

> In EclipseStore, the object graph *is* the heap. … JVM A's heap holds live references to
> objects JVM B is mutating. Each JVM's object graph is a cache with no invalidation protocol.
> So there is no design that gives you both in-heap access speed *and* shared mutable state.

No layer can defeat that, because it is the definition of ES's value. What *is* achievable
multi-JVM, in ascending cost:

1. **Safety** — two JVMs can never corrupt a store (ownership lease; an exclusive store lock,
   proven in a sibling project and lifted into this library).
2. **Fast failover** — one active JVM at a time, standby takes over (lease + handover).
3. **Read replicas** — other JVMs get a *near-real-time read-only copy* via binary change-log
   shipping (ES's own persistence chunks over a socket, using the ES serializer — this is the
   "send Java objects over a connection" idea, and it works *for replication*, not for shared
   writes).
4. **Write scale-out by partitioning** — N JVMs each exclusively own disjoint stores (tenants),
   with tenant-aware routing. Genuine multi-JVM writes, zero shared mutable state.

What is **not** achievable without giving up ES's reason to exist: N JVMs concurrently writing
the *same* store with in-heap speed. That is architecture B ("storage as a service") of the
multi-JVM proposal, and its conclusion stands: if you want that for the whole dataset, the answer
is PostgreSQL, not a worse reimplementation of it.

This library therefore targets **a complete single-JVM database experience, plus items 1–4** —
all of which are now built; see §1.1.

### 1.1 The unifying idea: auto-server mode

The ambition is not a layer specific to one application but a **generic product** — ES
extended with the database features every project needs, so they are never reinvented, and so
that *pointing two JVMs at the same storage is simply safe*. The drivers are all of: a second
process needing the data, HA/zero-downtime, read scale-out, and generality itself.

The unifying product feature that expresses items 1–4 of §1 as one user-visible behavior is
**auto-server mode**, and there is a well-loved precedent: H2's `AUTO_SERVER=TRUE`. In H2, the
first process to open a database file opens it embedded (full speed) and quietly starts a
listener; any later process that opens the same file discovers the owner and transparently
becomes a network client. No configuration, no corruption, no "what if two JVMs…" worry — the
question is simply dissolved.

zerozdb's version, laddered exactly on §1:

1. **Baseline:** a second JVM is *refused* (an exclusive store lock). Safe, blunt.
2. **Auto-server v1:** second JVM discovers the owner (endpoint written beside the store,
   guarded by the ownership lock) and becomes a **write-through client**. Corrected after
   review: a *remote* write cannot be an arbitrary lambda — the executing JVM must have the
   code. Remote writes ship as serializable command objects from the shared domain-model jar,
   which the owner also loads (§6.5). Local write-blocks stay lambdas.
3. **Auto-server v2:** the client additionally subscribes to the owner's commit log and holds a
   **live local replica graph** — reads local at heap speed (bounded staleness), writes
   forwarded. Per-read consistency choice: `db.read(...)` (local, fast, may trail sub-second) vs
   `db.readCurrent(...)` (routed to the owner, always current). Both are offered,
   and the default is local.
4. **Failover:** owner dies → lease expires → a client holding a full replica promotes itself.

Each rung subsumes the previous; the single-JVM core (§5) is rung 0 and every rung's engine.
The §1 boundary is untouched: no rung ever has two JVMs mutating one live graph — writers
funnel to exactly one owner per store, always.

**Two deployment shapes, one mechanism.** In pure peer auto-server
mode the owner role belongs to whichever app JVM opened the store first, and after a crash it
*wanders* to whichever peer promotes itself — safe (disk is the source of truth; an owner is
only the currently-elected writer) but operationally ugly: an interactive app can end up a
client of a batch JVM. This is rejected as the default experience, so the owner role carries a policy bit:

- **Peer mode** — role claimable by any JVM (H2-style; dev, tools, small installs).
- **Dedicated-server mode** — role pinned to processes started as `zerozdb-server` (a headless
  daemon owning the stores); app JVMs are always clients. This is the original
  "storage server on a port" topology, corrected to commit-granularity: clients hold live
  replica graphs (reads local, sub-second stale, `readCurrent()` for exactness), writes ship as
  write-blocks. Requires rung 3 — a dedicated server without client replicas would put every
  read on the wire, which is rejected Architecture B.

**Containers.** With apps in separate containers, peer mode's
premise — a shared local filesystem with trustworthy kernel file locks — decays: same-host
shared bind mounts break on any rescheduling, and file locks on network volumes (NFS/SMB/RWX)
are unreliable enough to be disqualifying for corruption safety. Kubernetes' ReadWriteOnce
volume semantics encode the correct rule: the store's volume mounts on exactly one node.
Container deployments therefore use dedicated-server mode exclusively: one `zerozdb-server`
container (StatefulSet + RWO PVC) owns the volume; app containers are stateless clients with
replica graphs. Consequences for the design, all cheap if decided now:

- **Discovery is an SPI**, not a file beside the store: filesystem endpoint file (peer mode),
  static address, DNS/K8s Service (container mode).
- **The lease/fencing layer is likewise an SPI** (file lock locally; Postgres
  advisory-lock/Infinispan/K8s Lease API in clusters). Its container-mode job narrows to the one
  case orchestrators get wrong: a partitioned node whose zombie server pod still writes while a
  replacement starts elsewhere. Fencing = an epoch check before each commit.
- **Failover is mostly the orchestrator's** (pod rescheduled, volume follows); zerozdb contributes
  fencing plus fast store-open.
- **Scale-out** = N server pods, each owning disjoint tenant stores on its own RWO volume.
- Positioning vs EDG: zerozdb requires no Kubernetes and no Kafka; it merely runs well under them —
  same jar from laptop `java -jar` to StatefulSet, differing only in discovery/lease plugins.

**Memory footprint of replicas.** Yes: every JVM holding a replica
graph pays heap for what it materializes — server ~full graph, each replica client its working
set. Three bounds keep this honest rather than naive 2×: (1) EclipseStore `Lazy` references
mean a client materializes only what it navigates (making "Lazy on large collections" a design
guideline for server mode); (2) partitioned stores have exactly one owner → zero duplication;
(3) every client-server database duplicates the hot set anyway (Postgres buffer cache + app-side
hydrated objects) — ES just makes the copy explicit and object-shaped.

Unceremonious owner death, either mode: in-flight unacked write-blocks fail with clean errors
(never torn — ES commit atomicity); every acked commit is on disk; OS releases the file lock at
process death; lease expires; an eligible process (any peer, or a standby server) opens the
store, replays to the last consistent commit, and takes over. Outage = lease timeout +
store-open time (store-open time is an unmeasured open question, carried over from the prior multi-JVM analysis). Data loss: none acked.

---

## 2. What exists already (verified)

### 2.1 EclipseStore itself (v4.1.0, May 2026)

- **Durable atomic commits.** A `Storer.commit()` is atomic at the storage level; on restart ES
  recovers to the last consistent commit. Crash recovery and online backup are ES's strong suit
  (multi-JVM proposal §2, capabilities 3 and 6: "solved").
- **The serializer is a standalone artifact** (`org.eclipse.serializer`, used by both consumers and
  zeroz4j at 4.1.0) — usable for wire transport independent of storage.
- **`LockedExecutor` / `StripeLockedExecutor`** exist in the serializer artifact — callback-style
  read/write lock wrappers. Verified limitation (a consumer's transaction design): callback-only, no
  lock handle, cannot span begin→commit. Useful shape precedent, not a building block.
- **GigaMap** (since ES 3, extended in 4.x): an indexed, lazily-loaded collection with off-heap
  bitmap indexes, a typed fluent query API (`map.query(firstName.is("John"))`), internal
  read/write locking, plus Lucene full-text and (4.x) vector indexes. This is ES's own answer to
  "indexed queries without a query language" and is directly relevant to our index layer (§5.6).
- **No** multi-process access, no MVCC, no network protocol, no conflict detection
  (multi-JVM proposal §2, capabilities 1, 2, 4, 5, 7: "no").

### 2.2 Prior art inside the family

- **A consumer's transaction design** — PROPOSED. Per-store transaction
  layer: RW lock spanning the transaction, before-images in identity-keyed side tables, deferred
  stores flushed once at commit, pluggable version strategy with read-time baseline capture,
  cross-store coordinator with total-order locking. Also documents *verified defects* in today's
  the CRM consumer layer: READ_UNCOMMITTED visibility, silent lost updates, rollback that does I/O.
  **A correction:** that consumer's begin/commit does *not* lock today
  — it defers stores and keeps an undo log, but concurrent visibility is uncontrolled (§2.3 of
  that doc). The locking is proposed, not present.
- **A consumer's multi-JVM analysis** — PROPOSAL. Five architectures analyzed;
  hybrid "partition by tenant + tiny shared Root service" recommended if ever needed.
- **an exclusive store lock** — already shipped in a consumer : a JVM refuses to
  open a store another JVM owns. This is capability 1 of §1 above, already written and tested.
- **`zeroz4j/docs/design/persistence-transactions.md`** — PROPOSED. Same problem, second
  codebase: per-tenant stores in a `ConcurrentHashMap`, raw `store()` calls, a real
  crash-between-two-commits bug, virtual-thread writes, TeaVM constraint (persistent types cannot
  carry framework state; framework must never *require* a base class).
- **External reference implementations, both examined and rejected as dependencies** (the CRM consumer
  transaction doc §5): XDEV `spring-data-eclipse-store` (working-copy model — breaks identity,
  non-atomic commit, memory leak) and Eclipse Data Grid (not on Maven Central, one store per
  cluster, Kafka+K8s mandatory, lost-write bugs fixed as recently as 2026-04). Revisit EDG
  yearly; today it is not consumable. **Re-verified while writing this:** Maven Central search for
  `g:org.eclipse.datagrid` → 0 artifacts; GitHub repo has no releases, no tags, and no commits
  since 2026-04-28. EDG has never shipped and currently appears stalled. Its value to zerozdb is as
  readable reference code for the commit-log tee/apply path, as validation that the need is
  real, and as a definition of the gap zerozdb fills: EDG is one-store-per-cluster with mandatory
  Kafka + Kubernetes; zerozdb is zero-infrastructure, multi-store, embedded-first.

### 2.3 The packaging tension, resolved

The the CRM consumer transaction doc's §6 advised: *"do not extract a shared library before either
consumer has run in anger."* This design has now decided standalone-first. These are reconcilable,
and honestly the situation has changed since that sentence was written: **two consumers'
independent designs converged on the same shape** (per-store scope, two enlistment modes,
side-table before-images, deferred flush, pluggable versioning). Convergence-before-code is
exactly the evidence that sentence was waiting for. The residual risk — designing API in a vacuum
— is mitigated by rule: **every zerozdb feature lands only alongside a consuming change in the CRM consumer
or zeroz4j.** The library is standalone in packaging, never in validation.

---

## 3. What "a more complete database" decomposes into, and the cost of each

| Capability | Feasibility | Cost | How |
|---|---|---|---|
| Atomic multi-object commit | ✅ trivial | S | One `Storer`, flushed once (the CRM consumer already does this) |
| Serialized writes, no torn reads | ✅ easy | S | Per-store RW lock + write-blocks (§5.2) |
| Rollback without I/O | ✅ easy | S–M | Before-images in identity side tables (§5.4) |
| Durability: ack = on disk | ✅ easy | S | Flush inside the write-block epilogue (§5.3) |
| Stale-edit detection (lost updates) | ✅ moderate | M | Version side table + read-time baseline SPI (§5.5) |
| Indexed queries, pure Java | ✅ moderate | M | Index registry maintained at commit + GigaMap bridge (§5.6) |
| Multi-user (threads) in one JVM | ✅ | — | Falls out of the above |
| Store-corruption safety across JVMs | ✅ shipped | S | Lift an exclusive store lock (§6.1) |
| Warm-standby failover | ✅ moderate | M | Lease + fencing via Postgres/Infinispan (§6.2) |
| Read-only replicas over the wire | ⚠️ hard but bounded | L | Binary change-log tee + shipping (§6.3) |
| Write scale-out via partitioning | ⚠️ mostly ops | L | Store ownership map + routing (§6.4) |
| Shared mutable graph across JVMs | ❌ **do not build** | ∞ | Contradicts ES's model (§1) |
| Pessimistic object locking | ❌ rejected | — | Needs bytecode weaving or discipline-by-hope |
| MVCC / working copies | ❌ rejected | — | Breaks object identity; see XDEV analysis |
| SQL/query language | ❌ out of scope | — | Decision: Streams + indexes only |

S ≈ days, M ≈ 1–2 weeks, L ≈ multiple weeks each, in focused effort.

---

## 4. Design principles

1. **Plain Java in, plain Java out.** Domain objects are unmodified POJOs. No annotations
   required, no bytecode weaving, no proxies, no mandatory base class (zeroz4j's TeaVM constraint
   makes this non-negotiable). Where a host framework *has* dirty-tracking hooks (the CRM consumer's
   an existing dirty-tracking hook), the library *uses* them; it never *requires* them.
2. **All bookkeeping lives in side tables keyed by reference identity**
   (`IdentityHashMap`) — versions, before-images, index membership. Persistent objects never
   carry framework state.
3. **The read path is untouched.** A read is a field access on a heap object — nanoseconds.
   The library adds at most one uncontended read-lock acquisition per read *block* (not per
   access). This is how ES's speed is preserved: zerozdb adds cost only where writes happen, and
   writes are "tens per second."
4. **Per-store engines, never a global one.** One `ZeroZDb` instance per `EmbeddedStorageManager`
   (per tenant/segment). Matches the CRM consumer (one storage per segment path) and zeroz4j (per-tenant
   map). A singleton would serialize many tenants against each other for nothing.
5. **Blocks run on the caller's thread.** `db.write(...)` acquires the store's write lock and
   runs the lambda *on the calling thread* — it does not hop to a dedicated writer thread.
   Same serialization guarantee (the lock admits one writer), but: exceptions propagate
   naturally, locks are reentrant, and — decisive for the CDI/Vaadin consumers — request-scoped
   context, security context, and `ThreadLocal`s remain intact. (A dedicated writer thread would
   re-create the WELD context-propagation and container-ThreadFactory problems the application consumer
   has already been bitten by.)
6. **Honest failure modes, stated in Javadoc.** Where a guarantee has a hole (e.g. §5.4's
   mutate-before-store caveat), the library documents it and provides a fallback rather than
   pretending.

---

## 5. The single-JVM core (v1)

### 5.1 API surface

```java
Esdb db = ZeroZDb.open(storageManager);           // wraps an existing EmbeddedStorageManager
// or ZeroZDb.open(root, path) to own the manager

// Reads: concurrent, cheap, consistent (no torn state possible while a block holds the read lock)
List<Prospect> hot = db.read(() ->
    root.prospects().values().stream()
        .filter(p -> p.getStatus() == HOT)
        .toList());

// Writes: serialized, atomic, durable
db.write(ctx -> {
    prospect.setPhone("555-1234");             // plain POJO mutation — the ES way
    prospect.setCity("Berlin");
    ctx.store(prospect);                       // explicit touch: enlist + mark for flush
});
```

`ctx.store(obj)` is deliberately the same verb and habit as ES's `storageManager.store(obj)` —
existing code migrates by wrapping, not rewriting. `db.read` overloads exist for `Runnable`-style
and value-returning blocks; `db.write` likewise.

### 5.2 Locking

One `ReentrantReadWriteLock` per store (fair=false). `read(...)` takes the read lock for the
block's duration; `write(...)` takes the write lock for mutation *and* flush — the span the
the CRM consumer doc identifies as the fix for its D1 defect ("uncommitted state is visible
process-wide"). Reentrant so nested blocks compose (`write` inside `write` joins the outer
transaction; `read` inside `write` is a no-op acquisition).

Read-heavy suitability: an uncontended `ReentrantReadWriteLock` read acquisition is ~20–50 ns;
readers run fully concurrently; the only reader stalls are the milliseconds during an actual
write flush. At tens of writes/second this is invisible. If profiling ever says otherwise, the
upgrade path is `StampedLock` optimistic reads behind the same API — an implementation detail,
not an API change.

**Fairness — measured, then changed.** The lock is constructed *fair*
(`new ReentrantReadWriteLock(true)`), against the usual advice, because the stress harness
(§10) showed the default barging lock starves readers under write saturation. 48 clients,
`OS_BUFFERED`, same machine:

| lock | writes/s | reads/s | write p50 | write max |
|---|---|---|---|---|
| unfair (default) | 3671 | **31** | 0.23 ms | **5747 ms** |
| fair | 2410 | **1606** | 5.67 ms | **145 ms** |

52× the read throughput and a 40× better tail for 34% of write throughput. For a database whose
stated workload is read-heavy — and whose reads back interactive UI — bounded read latency is
worth far more than peak write rate. Recorded here because the trade is deliberate and the
opposite default is the conventional one.

**Enforcement ladder.** The guard itself is one
static call: `DbGuard.mutation(obj)` reads a thread-local ("is a write-block active on this
thread?") and throws `IllegalMutationException` otherwise — nanoseconds. What varies is how the
call gets injected into plain POJO setters, so injection is an SPI with four rungs:

1. **Generated domain classes** (the CRM consumer's the model generator): one generator change guards
   all ~700 classes. Hard enforcement, zero magic, zero purity loss. Consumer #1's rung.
2. **Opt-in build-time weaving** (Gradle/Maven + ByteBuddy) for hand-written POJOs. The core
   library stays pure; enforcement is a build step a project chooses.
3. **Dev-mode Java agent** (`-javaagent:zerozdb-guard.jar`): instruments configured packages at
   class-load in dev/CI only; prod runs unmodified classes. Default for no-build-change projects.
4. **No-op floor** (zeroz4j/TeaVM): guard compiles away; API-shape discipline (read paths get
   setter-free interfaces) plus the honor rule.

Bonus on rungs 1–2: the same interception *auto-enlists* inside a block — before-image captured
and object marked dirty at first mutation — eliminating the "forgot `ctx.store()`" bug class and
closing §5.4's mutate-before-store rollback caveat. One mechanism, three weaknesses removed.
Independent of all rungs: `ctx.store()` outside a write block always throws, and
`db.assertReadContext()` exists for consumers' own assertions.

### 5.3 Commit and durability

At normal block exit: create one `Storer`, store every enlisted object and structure, single
`storer.commit()`, then (config: `Durability.SYNC`, the default) force the storage target before
releasing the write lock and returning. ES's commit is atomic — a crash mid-commit recovers to
the pre-block state; combined with the single flush point, a write-block is all-or-nothing on
disk.

**Verified against EclipseStore 4.1.0 sources: it never fsyncs.**
Its write path (`NioIoHandler.specificWriteBytes` → `XIO.write`) does plain channel writes; the
one fsync-capable method in the codebase (`XIO.appendAllGuaranteed`, containing
`fileChannel.force(false)` with the comment "this is the right place for a data-safety-securing
force/flush") has **zero callers**. Native ES durability is therefore process-death-only; a
power cut can lose acknowledged commits still in the OS page cache. zerozdb closes the gap:
`Durability.SYNC` (the default) wires `NioFileSystem.New(SyncedIo.wrap(NioIoHandler.New()))`
into the foundation — a delegating handler that follows every `writeBytes` with
`force(false)` (fdatasync semantics, the same call ES's own unused method makes).
`Durability.OS_BUFFERED` keeps native behavior for bulk loads. Shipped in M0/M1 with tests;
measured cost ≈ 5 ms per commit on the dev SSD.

### 5.4 Rollback (exception inside a block)

Adopts the CRM consumer §4.3 wholesale: on first enlistment, snapshot the object's flat fields into an
`Object[]` in an identity-keyed side map; on exception, restore snapshots, replay the structural
undo log (collection membership) newest-first, release, rethrow. No I/O on the rollback path;
works for never-stored objects. Memory cost O(dirty × fields), bounded by transaction size.

**The honest caveat of explicit touch:** if code mutates an object and only *then* calls
`ctx.store(obj)`, the snapshot is post-mutation and rollback cannot restore that first change.
Three-part mitigation: (a) `ctx.edit(obj)` — optional "enlist before mutating" verb for code
that wants perfect rollback; (b) hook SPI — hosts with setter hooks (the CRM consumer) enlist implicitly
at first mutation, closing the hole entirely; (c) fallback `Reloader.reloadFlat` from storage
for objects detected in the bad pattern, accepting the I/O. Documented, not hidden.

### 5.5 Stale-edit detection (the lost-update problem)

The the CRM consumer doc's D2 analysis applies to any interactive consumer: the conflict window is
*think time* (form open in a browser), so no lock can help and the baseline must be captured at
**read time**, not first mutation. Design: per-object version counters in a side table (or a
pluggable accessor when the domain has one, e.g. the CRM model framework `modifiedAt`), bumped at commit;
`db.baseline(obj)` captures, `ctx.storeChecked(obj, baseline)` throws `StaleObjectException` at
commit if versions diverged. **Opt-in per call site** — last-write-wins remains the default, so
the feature never taxes consumers that don't need it. The UI wiring (where to capture, what to
show the user) is consumer work by design.

### 5.6 Indexes and queries

Queries stay Java Streams over the live graph — zero library involvement, full speed. What the
library adds is *maintained secondary structures* so hot lookups don't scan:

```java
Index<String, Prospect> byCity = db.index(Prospect.class, Prospect::getCity);
Collection<Prospect> berlin = byCity.get("Berlin");   // O(1), no scan
```

Maintenance is the clever part, and the before-image machinery pays for itself twice: at commit,
for each dirty object the engine already holds the *old* field values — so it removes the old
index key and inserts the new one, precisely, with no full-object diffing and no annotations.
Indexes are themselves persisted objects (rebuild-on-open also supported as a config for
paranoia). Unique indexes throw on violation at commit, inside the write lock — a real
constraint, database-style.

**GigaMap bridge, not GigaMap dependency:** for collections that outgrow heap comfort
(millions of entities, need for off-heap bitmap/Lucene/vector indexes), the library's `Index`
SPI gets a GigaMap-backed implementation. GigaMap has its own internal locking and lazy
loading; the bridge's job is only to keep enlistment/commit semantics consistent. v1 ships the
plain heap index; the bridge is v2. This keeps the core dependency-free beyond
`org.eclipse.store:storage-embedded`.

### 5.7 Cross-store transactions

the CRM consumer needs them (provisioning writes 15 stores); zeroz4j doesn't yet. The design is the CRM consumer
§4.6 lifted as-is: a coordinator acquiring per-store write locks in total path order (deadlock
prevention), phase-1 validation of every participant (version checks, constraints) with all
locks held, phase-2 apply. Same honest limit: this is not distributed 2PC — process death
between store commits can leave torn state across stores; foreseeable failures all happen in
phase 1; the torn case is documented with a repair path. v1.5 feature, driven by the CRM consumer
adoption milestone.

---

## 6. Multi-JVM headroom (designed now, built when needed)

Ordered by the capability ladder of §1. The v1 deliverable for this whole section is **two SPI
seams plus documentation** — nothing else. Seams are cheap now and expensive to retrofit.

### 6.1 Ownership (build in v1 — mostly exists)

Lift the CRM consumer's shipped an exclusive store lock into zerozdb: exclusive file-lock per store directory,
refuse-to-open on conflict. This makes every later topology safe by default and costs nothing.

### 6.2 Lease-based failover (v2)

Architecture D of the multi-JVM proposal: an arbitrated lease (Postgres advisory lock or
Infinispan — both already in the consumer's production stack) with fencing tokens extends
ownership across hosts; a standby JVM opens the store on lease acquisition. "One JVM at a time,
deliberately" — honest HA without replication code.

### 6.3 Change-log shipping → read replicas (v3, the "serializer over a connection" idea)

The v1 seam: zerozdb owns the `EmbeddedStorageFoundation` setup, so it can interpose a
`PersistenceTarget<Binary>` tee — every committed binary chunk offered to an SPI
(`CommitLogListener`) *after* local durability. That seam is a few dozen lines and must exist
from day one (retrofit means re-plumbing storage creation in every consumer).

Built on that seam later: ship chunks over a socket (ES serializer's native format — no
translation layer, keeping the purity), replica JVMs apply them to their own store copy and
refresh read-only object graphs. This is Eclipse Data Grid's architecture minus Kafka,
Kubernetes, and the one-store-per-cluster limitation. **Risk statement, verbatim from the prior
analysis:** owning a replication protocol means owning subtle data-destroying bug classes — EDG
itself shipped lost-write bugs in this exact path fixed 2026-03/04. Hence: last on the ladder,
built only against a real read-scale-out need, and EDG re-evaluated first (if it has reached
Maven Central with per-store writer roles by then, buy beats build).

Replica consistency is *eventual* (typically sub-second). Replicas serve reads and analytics;
writes still route to the owner. This is exactly Postgres streaming replication's shape — ship
the effects, not the commands — which is the trade accepted when choosing lambda write-blocks
over serializable command objects.

### 6.4 Partitioned write scale-out (when a business case exists)

Architecture C: since every zerozdb engine is per-store, N JVMs each owning disjoint stores *is
already the library's model* — the library needs nothing new. What's needed is operational:
an ownership map with a single source of truth (the §6.2 lease machinery), tenant-aware request
routing, and runbooks. Plus one genuinely shared small store (the CRM consumer's Root) behind a thin RPC
service — the only place architecture B is appropriate, because its access pattern is
login-time, not per-request. Documented as the endgame topology; no v1 code.

---

## 6.5 Schema evolution and version skew 

**The classes are the schema.** There is no DDL: changing the "schema" means changing Java
classes in the domain-model jar. Two distinct problems follow — on-disk data written by old
classes, and *connected JVMs running different class versions*. They have different answers.

**On disk: ES Legacy Type Mapping, largely solved.** ES records every historical shape of every
class in its type dictionary. On load, data written by an old shape is mapped to the current
class: added fields default to null/zero, removed fields are dropped, matching fields carry
over — automatically. Renames and structural refactors need an explicit mapping entry
(old field → new field), written once per migration. Migration is lazy (objects convert as
they are next loaded/stored); an eager "rewrite all" pass is an optional maintenance command.
zerozdb adds only conveniences: a migration hook (`onOpen(fromVersion, toVersion)` for data
fix-ups that mapping can't express, e.g. splitting a name field), a schema-version stamp
stored in each store, and a refusal to open a store whose stamp is *newer* than the running
code (downgrade protection).

**Between JVMs: skew is refused, not tolerated (default).** The connection handshake compares
schema fingerprints — a digest of the model jar / type dictionary. Mismatch → the client is
rejected with a clear error (or, later opt-in, admitted read-only). This strictness is not
laziness; it prevents a specific data-destroying pattern: an old-schema client loads an object,
*doesn't know* about a field added in v2, and writes the object back — silently amputating the
v2 field for everyone. SQL databases tolerate additive skew because old clients name the columns
they touch; a serialized object graph carries whole objects, so tolerance must be engineered
per-field (diff-shipping) and is deferred until a real rolling-upgrade need exists.

**Upgrade choreography, stated as runbooks:**
- *Embedded/peer:* stop, deploy new jar, start — ES maps old data on load. Single JVM, trivial.
- *Dedicated server:* stop clients (or let them drain), upgrade + restart the server first (it
  owns the store, so it performs the on-disk migration via legacy mapping + hooks), then start
  upgraded clients; the handshake enforces that nothing mismatched ever connects. Blue-green
  friendly.

**Zero-downtime, re-analysed.**
Unpacking shows most of it was never lost:

- *Code-only releases* (the majority): client-side fingerprint unchanged → clients roll freely.
  Server-only bounce: clients serve reads from replicas throughout; with a client-side
  **write-queue** (writes buffer with a timeout during server absence — cheap, now in-design),
  a fast bounce is user-invisible.
- *The write-amputation hazard mostly dissolves in server mode:* remote writes are commands
  executed on the server with the server's classes — an old client's command sets only fields
  it names and cannot amputate newer fields. Skew danger is confined to the *read* path (an
  old replica can't deserialize new-shape commit-stream objects).
- *Therefore rolling schema upgrades reduce to expand/contract discipline* — the same rule
  zero-downtime SQL shops follow (add nullable fields first; never rename/remove in the same
  release; contract later). Additive shapes are exactly what an N-1 client can read through
  (its legacy mapping drops unknown fields on read; its writes are commands, so harmless).
- *And ES makes admittance decidable mechanically:* the type dictionary lets the handshake
  **diff the two schemas** and classify the mismatch — additive-only → admit N-1 client
  (rolling upgrade proceeds); breaking → refuse (choreographed upgrade). Strictness stays the
  default; zero-downtime is earned per-release by following expand/contract.

Net position: zero-downtime for all code releases and for additive schema releases;
a short choreographed window only for breaking schema changes — materially the same deal
PostgreSQL offers in practice. The additive-diff/N-1 admittance is v2 scope; v1 ships
strict-refuse plus the client write-queue.

**Consequence for the server:** the dedicated server loads the domain-model jar (it must — it
executes command objects, maintains indexes whose extractors are domain code, and performs
migrations). The server is therefore not domain-agnostic middleware; it is *your database
process*, versioned in lock-step with the model jar, closer to "stored procedures live here"
than to a generic daemon. Model-jar hot-reload on the server is future work; v1 upgrades the
server by restart.

---

## 6.6 Deployment modes: private and shared stores together 

A reasonable question: shared segments (Root, templates) must be reachable from several JVMs, while
each tenant segment is private to one JVM, needs transactions, and must not pay any replication
memory. Does that need a second, lighter product ("db-lite")?

**No — because the memory cost the question worries about only exists on a replica client.** An
owner holds exactly one copy of the graph; that is plain EclipseStore. What was missing was not
a lighter engine but a *mode switch*, so the same API serves both. `ZeroZDbNode.Mode`:

| mode | owns data | serves others | graph copies | use |
|---|---|---|---|---|
| `EMBEDDED` | yes | no (no socket at all) | 1 | per-tenant segments |
| `AUTO_SERVER` | if free, else client | yes when owner | 1 per owner, 1 per replica client | shared segments |
| `CLIENT_ONLY` | never | no | 1 replica if `localReads()` used | app JVMs against a dedicated server |

`execute` / `query` / `localReads` behave identically in all three, so application code is
written once. `localDb()` (lambda write-blocks, index registration, `CrossStoreWrite`) is
available wherever the node owns its data — i.e. always in `EMBEDDED`.

**Two constraints this shape imposes, which matter for the CRM consumer design:**

1. **Cross-store transactions require every participant to be locally owned.**
   `CrossStoreWrite` coordinates in-process `ZeroZDb` instances. the CRM consumer provisioning writes 14
   tenant stores *plus* Root atomically — if Root is served by another JVM and tenants are local,
   that operation cannot be one transaction. Options: keep Root local in the provisioning JVM
   (provisioning is rare and operator-initiated, so pinning it to the owner is reasonable); or
   accept a two-step protocol with a documented repair path. This is a design decision for
   the CRM consumer, and it is the sharpest consequence of splitting shared and private segments.
2. **A replica client is eventually consistent by design.** Shared-segment reads through
   `localReads()` may trail by one refresh; security and permission checks that must be exact
   should use `query(...)`, which always executes on the owner.

## 6.7 The standalone server 

`com.zeroz4j.db.server.ZeroZDbServerMain` is the real daemon (previously only an embeddable
class and a test harness main existed):

```
java -cp zerozdb.jar:my-domain.jar com.zeroz4j.db.server.ZeroZDbServerMain server.properties
```

Config lists stores by name, directory and root class; the daemon opens each, serves them all on
one port, publishes an endpoint file beside every store (so `CLIENT_ONLY` nodes need no port
configuration), logs open time per store, and shuts down cleanly.

**Shutdown, honestly, per platform:** on Unix/containers SIGTERM (`docker stop`,
`kubectl delete`) runs the JVM shutdown hook. On Windows `Process.destroy()` is
`TerminateProcess` — a hard kill; hooks never run. The daemon therefore *also* treats stdin EOF
as a shutdown signal, which works everywhere. A hard kill is survivable regardless: acknowledged
writes are already durable; only a stale endpoint file is left behind, and a client that cannot
connect falls back to taking ownership.

## 6.8 Cross-host ownership for containers 

OS file locks are unreliable on NFS/SMB, so ownership is now an SPI —
`com.zeroz4j.db.lease.OwnershipArbiter` — with two implementations:

- **`FileLockArbiter` (default).** Kernel file lock: instant takeover on process death, no
  tuning, correct on local disks and Kubernetes RWO volumes.
- **`LeaseFileArbiter`.** A `zerozdb.lease` file holding owner id, **epoch** and expiry, renewed
  on a heartbeat and replaced atomically. A challenger may take over only after observing expiry
  plus a grace period, and does so with `epoch + 1`; the incumbent notices the epoch change (or
  its own renewal failure) within one heartbeat and **steps down** — closing its server and
  store rather than writing alongside a new owner. `forceAcquire` exists for the operator case
  where a pod is known dead and waiting out the lease is unacceptable.

**The limit, stated plainly.** With heartbeat *h*, lease *3h* and grace *g*, a new owner appears
no sooner than *3h + g* after renewals stop, and a displaced owner stops serving within *h*.
A window remains only if a process is frozen (long GC, VM suspend, SIGSTOP) beyond the lease and
resumes mid-write. Closing it completely needs **storage-level fencing** — the store rejecting
writes that carry a stale epoch — which EclipseStore's file format does not offer. This is the
same trade every lease-based election makes without fencing tokens, and it is documented rather
than glossed. For maximum safety on shared storage, prefer one RWO volume per store (the
platform then enforces single-node mount) and use the lease as defence in depth.

Wiring: `ZeroZDbNode.builder(...).arbiter(new LeaseFileArbiter()).ownerId("pod-7")`. Because an
arbiter already holds the exclusive claim, the node opens the store with
`ZeroZDb.openUnguarded(...)` — taking the built-in lock as well would collide with itself inside
one JVM (a real bug the suite caught during this work).


## 6.9 Schema evolution: what actually happens, and how to enforce it 

Written after building real two-version tests (`SchemaEvolutionTest` compiles two versions of a
class and runs each in its own JVM — the only faithful way to reproduce "deploy v2, roll back to
v1"). Three results, one of them a genuine hazard.

**1. Additive changes keep rollback possible — proven.** v1 writes, v2 adds a field and reads
the old record (new field defaults), v2 writes new records, then **v1 reads both back and keeps
writing**. This is the property the release strategy in §6.10 depends on, and it now has a test.

**2. EclipseStore's legacy mapping matches leftover fields by type similarity, not only by
name.** A renamed field therefore keeps its data automatically — convenient, and the reason for
the next point.

**3. The hazard: remove one field and add an unrelated field of the same type in one release,
and the old value is silently carried into the new one.** No error, no log, wrong data. An
intended rename is indistinguishable from an accidental swap at runtime.

**Response — two mechanisms, both shipped:**

- **`SchemaEvolution.strict()` is now the default** (a deliberate divergence from EclipseStore,
  in the direction of "missing data beats wrong data"). A match validator accepts only
  same-name pairs, so similarity guesses are refused and a vanished field arrives unset.
  Renames become explicit: `SchemaEvolution.strict().rename("com.x.Product#sku",
  "com.x.Product#productCode")`, backed by ES's `PersistenceRefactoringMappingProvider`.
  `SchemaEvolution.lenient()` restores ES behaviour. *Implementation note learned by test: the
  validator is consulted for every candidate pairing, not just heuristic leftovers — rejecting
  everything discards unchanged fields too.*
- **`SchemaCompatibility` — build-time enforcement**, which is the answer to "I can't think of a
  way to enforce this". Capture the persistent shape of your classes as a committed
  `SchemaDescriptor` file; a test compares the current classes against it and fails the build,
  classifying every change: added field/class = SAFE; removed field, changed type, removed class
  = ROLLBACK_BREAKING; removed-and-added of the same type in one class = **CRITICAL** (the
  teleport pattern). Intentional changes are accepted by regenerating the baseline in the same
  commit, so a schema change is reviewed like an API change instead of happening by accident.

## 6.10 Zero-downtime releases with frequent deploys 

The constraint: a store's classes and its owning JVM are welded together, so the goal is to keep
frequent releases away from that joint.

1. **Shared segments (Root, templates) live in the standalone daemon** (§6.7), versioned
   independently of the app. Blue and green are both `CLIENT_ONLY` against it; deploying the app
   several times a day never restarts it. Only a change to *Root/template classes* needs a daemon
   restart — a planned, rare window. Set the daemon's `schemaId` from the shared model version,
   never from the app version, or every app deploy would lock the new build out for no reason.
2. **Tenant segments stay `EMBEDDED`** in app JVMs, so a release means a per-tenant handover
   (store close, store open ≈ 150 ms measured on a small store). Rolling tenant-by-tenant turns a
   global outage into a sequence of sub-second interruptions; this is what makes tenant-aware
   routing a real prerequisite rather than a nicety.
3. **Rollback is the risk, not rollout.** EclipseStore migrates old data forward, never backward.
   Keep every release additive (expand/contract) and rollback stays possible — enforced by
   §6.9's build gate rather than by discipline alone.

Rejected for this consumer: putting tenant data in daemons too. It gives perfect zero-downtime
app releases and stateless app JVMs, but costs either remote-read latency or replica memory —
exactly what the owner ruled out.

## 6.11 Security 

Previously the server bound to loopback only, with no authentication and no transport security —
fine for a same-host experiment, disqualifying for containers. Now:

- **`bindAddress`** (default `127.0.0.1`, so a store is never exposed by accident). Binding to
  any other interface **without a secret is refused at startup** with an explanatory error.
- **Shared-secret authentication**, compared in constant time. A rejected client is told only
  "authentication failed" — not whether the secret or the schema was wrong, and it receives an
  empty store inventory, so an unauthenticated caller learns nothing about the deployment.
- **TLS** via `Tls.server(keystore, password)` / `Tls.client(truststore, password)`; the client
  API takes secret and SSLContext together. Tested with a real keytool-generated certificate.

Still open: per-client identities and authorisation (today's secret is all-or-nothing across all
stores on a server), rate limiting and connection caps, and audit logging of commands.

## 7. What zerozdb explicitly will not do

1. **Shared mutable graph across JVMs** — see §1. The library's docs must say this plainly so
   nobody "finishes" it into corruption.
2. **Pessimistic per-object locking** — requires intercepting plain field writes (bytecode
   weaving) or unenforceable discipline. Rejected in both prior designs; rejected here.
   (Advisory *application-level* locks like zeroz4j's `LiveMutexManager` or the CRM consumer's
   Infinispan an application-level lock service remain fine — they're UX features, not storage integrity.)
3. **Working copies / MVCC** — breaks `==` identity, which XRI-style addressing and the live
   graph model depend on; the XDEV implementation's measured flaws stand as the cautionary tale.
4. **A query language** — Streams + indexes, per owner decision. (GigaMap's fluent conditions
   arrive with the bridge for those who want them; still Java, still no parser.)
5. **Schema migration magic** — ES's own Legacy Type Mapping handles class evolution; zerozdb
   documents patterns but adds no machinery in v1.

---

## 8. What is built

Everything in this document is implemented unless explicitly marked otherwise, and covered by 107
tests including multi-process harnesses that kill JVMs mid-write and verify invariants across
process boundaries.

**The engine:** write-blocks and span-style transactions, atomic durable commits with the fsync
EclipseStore omits, in-memory rollback from before-images, maintained indexes with unique
constraints enforced pre-commit, stale-edit detection, and cross-store writes with total-order
locking.

**The network layer:** a server executing command and query objects, auto-discovery, self-promoting
failover, client-side replicas reading at heap speed, per-store schema negotiation, a standalone
daemon, an operations console, and cross-host ownership by renewable lease.

**Deliberately not built**, each for a reason given in the text above: incremental replication
(§1.1 — refresh ships whole snapshots, which suits read-heavy stores), a client-side write queue,
store eviction, per-client authorisation, and metrics beyond the console overview.

A rule held throughout: **no feature landed without a consuming application changing alongside
it.** That is what kept the API shaped by use rather than by speculation, and it is why several
sections below record corrections rather than plans.


## 10. Stress harness and measured behaviour 

`src/test/java/org/zeroz4j/db/stress/` is a test *application*, not a unit test: N Loom
virtual-thread clients hammer a live engine with four mixed workloads, each carrying an
invariant that only holds if the engine is correct.

- **Bank transfers** — money moved between random accounts inside one write-block; readers
  continuously sum all balances. A torn read or lost update breaks the exact-total invariant.
- **Contended counter** — many clients incrementing one object through
  `baseline`/`storeChecked` with retry; the final value must equal the number of successful
  commits, proving stale detection rejects without losing work.
- **Catalog churn** — concurrent adds/removes against a small SKU pool so the unique index is
  contended; catalog size must equal index membership, and both indexes must agree.
- **Cross-store transfers** — a transfer plus an audit entry in a second store via
  `CrossStoreWrite`, concurrent with plain writes on both stores.

Then the store is reopened and the money invariant re-checked against disk.

`StressTest` runs a 5-second, 48-client version with the suite. Soak runs:
`java -cp <test-cp> com.zeroz4j.db.stress.StressHarness <clients> <seconds> [SYNC|OS_BUFFERED]`.

**Measured (48 clients, 10 s, dev laptop SSD, fair lock):**

| durability | writes/s | reads/s | write p50 | write p99 |
|---|---|---|---|---|
| `SYNC` (default) | 281 | 188 | 49 ms | 467 ms |
| `OS_BUFFERED` | 2410 | 1606 | 5.7 ms | 77 ms |

Reading these honestly:

- **fsync costs ~10× write throughput.** That is the price of "acknowledged means power-cut
  safe", and it is the correct default; `OS_BUFFERED` exists for imports and rebuildable data.
- **These are saturation numbers, not the target workload.** 38 of 48 clients writing
  flat-out is a load the design explicitly does not target ("read-heavy, tens of writes per
  second"). At that real rate, write latency is the p50 (a few ms) and readers never queue.
- **The single-writer ceiling is now quantified:** ~2.4k commits/s buffered, ~280/s synced, per
  store. Since stores are independent, a 136-tenant deployment multiplies this by store count.
- **Store open ≈ 150 ms** for a small store — the first real datum for the failover-window
  question inherited from the CRM consumer multi-JVM proposal (§7.2 there). Still needs measuring at
  realistic store sizes.
- Every invariant held in every run, including under deliberate unique-constraint contention
  and cross-store writes.

### 10.1 Multi-JVM harness 

`MultiJvmStress` orchestrates a genuine multi-process run: one **server JVM** owning the store
(`ServerMain`) and N **client JVMs** (`ClientMain`), each opening several connections and
driving Loom virtual threads over real TCP. Invariants now span processes:

- **money** — total balance across all accounts, checked by clients on every read *and* by the
  server at shutdown *and* again after reopening the store in a fresh JVM;
- **counter** — the server's counter must equal the sum of increments independently counted by
  every client JVM (proves no remote write is lost or double-applied);
- **index** — catalog size equals both index memberships, under deliberate unique-SKU
  contention across processes.

`MultiJvmStressTest` runs a 2-JVM × 6-thread × 6 s version with the suite. Bigger runs:
`java -cp <test-cp> com.zeroz4j.db.stress.MultiJvmStress <clientJvms> <threadsPerJvm> <seconds> [SYNC|OS_BUFFERED]`

**Measured (dev laptop, all invariants held in every run):**

| topology | durability | remote writes/s | remote reads/s |
|---|---|---|---|
| 3 client JVMs × 8 threads | `OS_BUFFERED` | 3566 | 1456 |
| 6 client JVMs × 16 threads | `SYNC` | 272 | 108 |

Remote throughput tracks embedded throughput closely (3.6k/s vs 2.4k/s buffered; 272/s vs 281/s
synced), which is the expected result: the bottleneck is the single writer and the fsync, not
the wire. Serialization and TCP are noise next to a commit.

**Harness lesson worth keeping:** the first multi-JVM runs "hung" and looked like a database
deadlock. The cause was the classic `ProcessBuilder` trap — nobody drained the server JVM's
stdout after the readiness line, its pipe buffer filled, and the server blocked on its next log
write. The fix is a dedicated drain thread. Recorded because the symptom is indistinguishable
from an engine hang and cost a debugging round.

### 10.2 Failover harness 

`FailoverStress` starts N JVMs on one store in auto-server mode, lets them work, then
**hard-kills the owner mid-flight** and watches what the survivors do.

The invariant is exact rather than statistical. `Increment` returns the counter value it
produced, so every node remembers the highest value it ever had *acknowledged*. Once all JVMs
have exited, the store is opened directly and its counter must be **at least** that value — if
a promotion had resurrected older state, the counter would have gone backwards and some client
would have been told about a write that no longer exists.

**Measured (3 JVMs, 24 s, `SYNC`):** owner killed mid-flight → a survivor promoted itself →
survivors acknowledged 5591 further increments → highest acknowledged value 6673 → counter
after reopen **6673**. No acknowledged write lost, no manual intervention, no configuration.

Two honest notes:

- Promotion here is guarded by the OS file lock, which the kernel releases on process death.
  That is correct on a local filesystem and **not** sufficient across hosts on a network share
  — the lease/fencing SPI (§6.2) is what makes cross-host failover safe, and it is not built.
- Requests in flight at the instant of the kill fail and are retried by the node; unacknowledged
  writes may or may not have landed, exactly as with any database.

### 10.3 Replica measurements 

The design's central claim is that shipping *commits* rather than *objects-per-access* keeps
EclipseStore's speed in a client-server topology. Measured on one node pair, same machine:

| read path | per read | note |
|---|---|---|
| `client.query(...)` — executes on the owner | 368 µs | always current |
| `node.localReads().read(...)` — replica | **130 ns** | stale by ≤ one refresh |

**2835×.** That is the difference between a database call and a field access, and it is why
Architecture B (per-access remoting) was rejected in §3: a design that puts every field read on
the wire pays the database's cost without getting the database.

Verified alongside the number, because speed without correctness is worthless:

- **No torn snapshots.** A writer sets two fields together 60 times while a reader performs 3000
  replica reads; the reader never observes them differing (`ReplicaTest`).
- **Replicas are copies, not references.** Scribbling on a replica's graph cannot corrupt the
  owner's.
- **Local reads really are local.** 1000 replica reads cause at most a handful of server
  requests (background refresh only), asserted rather than assumed.

## 9. Open questions

1. **Read isolation is only half closed.** Writers serialise, but a host framework that reads the
   graph directly rather than through `db.read(...)` is not excluded from a transaction in flight.
   Closing it fully means routing every read through the engine, which is a large change for a
   symptom nobody has reported; it is recorded here rather than pretended away.
2. **Long write-blocks starve readers.** The lock is fair, so a block that holds the write lock for
   seconds blocks every reader for that long. The guidance — compute outside the block, mutate
   inside — belongs in the API docs, but nothing enforces it.
3. **Incremental replication.** Refresh currently ships a whole snapshot. Shipping the commit's
   changed objects instead would make replica cost proportional to change rather than to graph
   size, at the price of owning a replication protocol — a class of code where bugs destroy data
   quietly. Deliberately deferred until a real read-scale-out need exists.
4. **Storage-level fencing.** Lease-based ownership leaves one window: a process frozen beyond its
   lease could resume mid-write. Closing it needs the storage layer to reject writes carrying a
   stale epoch, which EclipseStore's file format does not offer.
5. **Per-client identity.** Authentication is one shared secret per server. Per-client identities
   and per-store authorisation are unbuilt, which rules out multi-tenant server deployments where
   clients should not see each other's stores.
