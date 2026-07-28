# Changelog

## 0.1.0 (unreleased)

First working version. Built 2026-07-27/28 and proven against a real consumer application, which
is where several of the corrections below came from.

### Engine
- Write-blocks (`db.write` / `db.writeResult`) with one atomic, durable commit per block; nested
  blocks join the outer commit.
- `WriteTransaction` (`db.beginWrite()`) for host frameworks with a `begin()`/`commit()` API.
  Nested transactions join; a nested rollback poisons the outer commit; `close()` rolls back a
  leaked transaction so a store cannot be wedged.
- Rollback from before-images: no I/O, and works for objects the store has never held.
- `Durability.SYNC` (default) adds the `fsync` EclipseStore never performs — verified against its
  4.1.0 sources, where the only `force()` call has no callers. `OS_BUFFERED` keeps native speed.
- Concurrent reads via `db.read`; **fair** read/write lock, chosen after measuring 31 reads/s
  under write saturation with the default barging lock versus 1606/s fair.
- Lock upgrades (starting a write inside a read block) are refused with an explanation instead of
  deadlocking.
- Exclusive store ownership; `openUnguarded` for callers that already hold a claim.

### Data
- Maintained `Index` and `UniqueIndex`; constraint violations abort before anything persists.
- Stale-edit detection: `db.baseline(obj)` + `ctx.storeChecked(obj, baseline)`.
- `CrossStoreWrite` — several stores in one operation, total-order locking, phase-1 validation.
  Not distributed 2PC, and documented as such.

### Schema
- `SchemaDescriptor` / `SchemaCompatibility`: capture the persistent shape, diff it in the build,
  classify changes SAFE / ROLLBACK_BREAKING / CRITICAL.
- `SchemaEvolution.strict()` is the **default**, diverging from EclipseStore deliberately: its
  similarity heuristic silently carries data between unrelated fields of the same type. Renames
  are declared; `lenient()` restores the original behaviour.

### Distribution
- `ZeroZDbServer` / `ZeroZDbClient`: commands and queries over TCP, EclipseStore's own serializer
  as the wire format, engine exceptions preserved across it.
- `ZeroZDbNode` with `EMBEDDED` / `AUTO_SERVER` / `CLIENT_ONLY` modes and one API for all three;
  auto-discovery via an endpoint file; self-promotion when the owner dies.
- `ReplicaView`: client-side graph refreshed by long poll. Measured 130 ns per local read versus
  368 µs for the same read as a remote query.
- `OwnershipArbiter` SPI — `FileLockArbiter` (local, default) and `LeaseFileArbiter` (cross-host,
  heartbeat lease with a fencing epoch and step-down).
- Standalone daemon `ZeroZDbServerMain`; stops on SIGTERM and on stdin EOF (Windows has no
  SIGTERM).
- Security: configurable bind address that refuses non-loopback without a secret, constant-time
  shared-secret authentication that discloses neither the failing check nor the store inventory,
  TLS.

### Tooling
- `ConsoleServer`: overview, domain-aware graph browser, named queries via `QueryCatalog`, model
  diffed against the committed schema baseline. Read-only, gated, no web framework.

### Verification
104 tests, including a kill-9 durability proof, a multi-JVM harness with cross-process
invariants, a failover harness that hard-kills the owner, and two-version schema-evolution tests
run in separate JVMs.

### Known gaps
Store eviction; incremental replication; per-client identity and authorisation; metrics;
published artifacts. See the guide's "Known limits".
