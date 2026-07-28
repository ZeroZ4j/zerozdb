# Glossary

Terms used precisely in this documentation. Several are ordinary words with a specific meaning
here, and confusing them is the source of most misunderstandings.

---

**Before-image** — a snapshot of an object's fields taken when it is *enlisted*, used to restore it
if the write-block fails. Taken at enlistment, which is why `ctx.edit(obj)` before mutating gives a
faithful rollback and `ctx.store(obj)` after mutating does not.

**Commit** — one atomic write to disk. A write-block produces exactly one, no matter how many
objects it enlisted. Not the same as a git commit, obviously, but the atomicity intuition carries.

**Command** (`DbCommand`) — a unit of work executed *where the data is*, sent as an object rather
than a lambda because the executing JVM must have the code. Must be a plain class, never a record.

**Durability** — whether an acknowledged write survives failure, and which failure. `SYNC` (the
default) survives power loss; `OS_BUFFERED` survives process death but not a power cut.

**Enlist** — to tell a write-block that an object changed, with `ctx.store(obj)` or `ctx.edit(obj)`.
Enlisting does **not** cascade into referenced objects; every changed level is enlisted separately.

**Epoch** — a monotonically increasing number identifying successive store owners under a lease.
A resurrected owner carrying a stale epoch can be detected.

**Index** — a maintained lookup structure over a source Map or Collection, kept correct at every
commit. Not a database index in the storage sense; it lives in memory alongside the graph.

**Lease** — a renewable, expiring claim on a store, used where OS file locks cannot be trusted
(network volumes, containers across hosts). Contrast with the **file lock**, which the kernel
releases on process death and is correct on local disks.

**Node** (`ZeroZDbNode`) — a JVM's participation in a store, in one of three modes. The API is
identical in all three, so application code does not know which it is.

**Owner** — the single JVM that holds a store's data and performs its writes. Exactly one per
store, always. Everything else is a client.

**Query** (`DbQuery`) — a read executed where the data is, returning a value. Returns *values*, not
live graph nodes: whatever it returns is serialized to the caller.

**Replica** — a client's local copy of a remote store's graph, refreshed the instant the owner
commits. Read at heap speed, stale by up to one refresh.

**Root** — the single object from which the whole persistent graph is reachable. Everything
persisted hangs off it directly or indirectly.

**Schema** — in this database, the classes themselves. There is no separate schema definition, so a
schema change *is* a code change.

**Stale edit** — the think-time conflict: a user opens a form, someone else saves, the first user
saves over them. No lock helps, because there is no moment of concurrent access. Detected by
capturing a **baseline** when the edit begins.

**Store** — one EclipseStore database directory with one root graph. An application typically has
many — one per tenant is the common shape — and they are independent.

**Strict matching** — the default schema-evolution policy: only same-name fields are matched when
loading older data. Contrast **lenient matching**, EclipseStore's own default, which also pairs
leftover fields by type and can move data between unrelated fields silently.

**Torn read** — observing a change halfway applied, for example a phone number updated but the
address not yet. Prevented inside `db.read(...)` blocks and inside replica snapshots.

**Write-block** — `db.write(ctx -> …)` or `db.writeResult(ctx -> …)`. One at a time per store, one
atomic commit, rollback on failure. The unit of work.

**Write-through client** — a client that sends its writes to the owner rather than writing storage
itself. All clients are write-through; there is no other kind.
