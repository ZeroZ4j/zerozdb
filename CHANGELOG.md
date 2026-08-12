# Changelog

## 0.2.0

Diagnostics for a write-block that was never ended. Reported by a consumer application on
2026-08-08: `close()` parked for ever on a leaked write transaction, with no log line, no
exception and no way to ask who held the lock. The leak was the consumer's; the silence was ours.

### Engine
- **`close()` is bounded.** It waits up to 30 seconds (`ZeroZDb.DEFAULT_CLOSE_TIMEOUT`, or
  `-Dzerozdb.closeTimeoutSeconds=N`) and then throws `StoreBusyException` naming the thread that
  holds the block, instead of parking silently for the life of the process. `close(Duration)`
  takes the timeout per call. On expiry the store stays open: shutting the storage manager down
  underneath a writer that may yet wake would trade a diagnosable hang for an undiagnosable
  corruption.
- **The write-block holder is observable.** `hasOpenWriteBlock()`, `writeBlockOwner()` and
  `describeLockState()` answer "is a write block open on this store, and who opened it" — the
  question `isWriteActive()` cannot answer, because it reads a ThreadLocal and so only ever
  reports on the calling thread. A host can now assert at the end of a request that it left
  nothing open.
- **Write-block origins can be traced.** `ZeroZDb.traceWriteBlockOrigins(true)` or
  `-Dzerozdb.traceWriteBlockOrigins=true` records the stack that opened each block, turning
  "something leaked a write-block" into the line that leaked it. Off by default.
- **A leaked `WriteTransaction` reports itself.** One collected without `commit()`, `rollback()`
  or `close()` logs a warning naming the thread that opened it — what a connection pool does about
  a leaked connection, for the same reason. Nothing can repair it: only the owning thread can
  release its write hold, and it has forgotten the transaction exists.
- **Closing from inside a write-block is refused** rather than shutting the storage manager down
  underneath the block that is about to commit into it.
- `lock()` no longer sits outside the `try` that releases it in `writeResult`, `beginWrite` and
  `beginCross`. A narrow window, but the consequence was the permanent hang above. `beginCross`
  in particular could leave the lock held if set-up failed after its own guard, and
  `CrossStoreWrite` would never release it, because a store it failed to begin is not a
  participant.

### Dependencies
- **EclipseStore 4.1.0 → 4.2.0** (storage and serializer). No source change was needed and all
  116 tests pass on it, including the kill-9 durability proof and the multi-JVM harnesses.
- 4.2.0 fsyncs where 4.1.0 never did, so the durability claim in `Durability`, the README and the
  design document is now stated more precisely: EclipseStore still does not force the commit path,
  but it does force the outgoing head file at rollover, and the head plus transactions file before
  a housekeeping delete. `Durability.SYNC` is still what makes an acknowledged commit survive
  power loss — the new barriers narrow which *older* files a crash can strand, not which commit.

### Verification
116 tests. The nine new ones state what a store says while a block is open, when one is leaked,
and when a transaction is dropped without ending.

## 0.1.0

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
