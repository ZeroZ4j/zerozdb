# AGENTS.md — working on or with ZeroZ DB

Instructions for AI coding agents. Humans should start at [README.md](README.md) and
[docs/Guide.md](docs/Guide.md).

ZeroZ DB is a pure-Java database over [EclipseStore](https://eclipsestore.io) at version **0.1.0**.
Plain Java objects hanging off a root object *are* the database; ZeroZ DB adds serialized atomic
transactions, crash durability, maintained indexes and unique constraints, conflict detection,
cross-store writes, an optional network server, and schema-evolution safety. There is no mapping
layer, no query language and no SQL.

Part of the ZeroZ4J family (zeroz4j.com), alongside
[ZeroZ Stack](https://github.com/ZeroZ4j/zerozstack), the full-stack framework.

## Build and test

Requires **JDK 21** (virtual threads) and Maven 3.9+.

```bash
mvn install          # build and install locally
mvn test             # 107 tests, about two minutes
mvn javadoc:javadoc  # API docs into target/reports/apidocs
```

Published to Maven Central as `com.zeroz4j:zerozdb:0.1.0`. Building locally is only needed when
working on the library itself.

The suite includes multi-process harnesses that spawn child JVMs, kill them mid-write and verify
invariants across process boundaries. They take longer than unit tests and are worth the wait; do
not disable them to make a run faster.

## The rules that matter most

These cause more incorrect generated code than anything else.

**Enlisting does not cascade.** `ctx.store(obj)` covers that object only. Changing a field on the
root *and* a map hanging off it means enlisting both. Getting this wrong loses data silently — the
change is in memory and never reaches disk.

**Every write goes inside a write-block.** `db.write(ctx -> …)` returns nothing;
`db.writeResult(ctx -> …)` returns a value. They are separate methods because Java cannot
disambiguate the overloads for a lambda ending in `throw`. Everything enlisted lands in one atomic
commit that is on disk when the call returns.

**Enlist before mutating** with `ctx.edit(obj)` when rollback fidelity matters: the snapshot is
taken at enlistment, so one taken afterwards already contains the change.

**Never start a write inside `db.read(...)`.** A read lock cannot be upgraded, so it would deadlock
against itself; the engine throws instead. Reading inside a write is fine — that is a downgrade.

**`DbCommand` and `DbQuery` must be plain classes, never records.** EclipseStore's serializer
reaches fields directly and the JVM refuses that for records without
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`. It fails at the first remote call, not at
compile time, so it looks like a networking bug. Use public fields and a public no-arg constructor.

**Schema matching is strict by default.** A renamed field arrives unset rather than being filled
from an unrelated field of the same type. Declare renames with
`SchemaEvolution.strict().rename("com.x.Product#sku", "com.x.Product#code")`. This diverges from
EclipseStore deliberately: its lenient matching pairs leftover fields *by type* and can move data
between unrelated fields with no error.

## Architecture in one pass

- `com.zeroz4j.db` — the engine. `ZeroZDb` is the entry point; `WriteContext` and
  `WriteTransaction` are the write API; `Snapshots` provides before-images; `IndexImpl` maintains
  indexes at commit; `SyncedIo` adds the fsync EclipseStore omits.
- `com.zeroz4j.db.net` — the network layer. `ZeroZDbServer` executes commands against the live
  graph, `ZeroZDbClient` sends them, `ZeroZDbNode` presents one API across EMBEDDED, AUTO_SERVER
  and CLIENT_ONLY modes, and `ReplicaView` keeps a client's local copy fresh.
- `com.zeroz4j.db.schema` — `SchemaDescriptor`, `SchemaCompatibility` (the build-time gate) and
  `SchemaEvolution` (strict versus lenient field matching).
- `com.zeroz4j.db.lease` — `OwnershipArbiter` and its two implementations, file lock and lease file.
- `com.zeroz4j.db.console` — the read-only operations console.
- `com.zeroz4j.db.server` — the standalone daemon.

## Conventions

**Every source file carries the licence header** with the project and author links. Copy it from any
existing file when adding one; it is deliberate, not boilerplate to trim.

**Comments explain why, not what.** Several classes carry a note about a decision that looks wrong
until you know the reason — the fair lock, strict schema matching, `writeResult` existing
separately. Do not "tidy" those away, and do not restate what the code already says.

**Tests state behaviour, not implementation.** Test names are sentences about guarantees. Several
exist because a plausible-looking implementation was wrong; the comments in them record what failed.

**Honest documentation.** `docs/reference/limitations.md` lists what this database is not for. When
you add a limitation, add it there rather than omitting it.

## What not to do

- Do not disable or weaken the multi-process tests to speed up a run.
- Do not change the fair `ReentrantReadWriteLock` back to the default. It was measured: the barging
  default starved readers to 31 reads/s against 1606 fair, for 34% more write throughput.
- Do not remove `--pinentry-mode loopback` from the GPG plugin config; releases hang without it.
- Do not add a query language or an ORM layer. Queries are Java streams plus maintained indexes,
  and that is the design rather than a gap.
- Do not claim the API is stable. This is 0.x and `docs/Guide.md` states which parts are settled.

## Reference

- [docs/Guide.md](docs/Guide.md) — the developer guide, and the place to look first.
- [docs/start/quickstart.md](docs/start/quickstart.md) — smallest working program.
- [docs/guides/troubleshooting.md](docs/guides/troubleshooting.md) — failures that are silent or
  misleading, and what each actually means.
- [docs/reference/limitations.md](docs/reference/limitations.md) — what this is not for.
- [docs/Feasibility-And-Design.md](docs/Feasibility-And-Design.md) — why it is built this way,
  including the alternatives rejected and the measurements that settled arguments.
- [RELEASING.md](RELEASING.md) — how a maintainer cuts a release.
