# Troubleshooting

The failures worth documenting are the ones that are silent, or that say something other than what
they mean. Most of these were found the hard way.

## My change vanished after restart

**Almost always: you enlisted the wrong object.** Enlisting does not cascade — `ctx.store(root)`
covers the root's own fields, not the map hanging off it.

```java
db.write(ctx -> {
    root.products.put("SKU-1", product);
    ctx.store(root);              // WRONG: the map changed, not the root
});

db.write(ctx -> {
    root.products.put("SKU-1", product);
    ctx.store(root.products);     // right
});
```

If you changed a field on the root *and* the map, enlist both.

## Rollback did not restore my object

The snapshot is taken when the object is enlisted. If you mutate first and enlist afterwards, the
snapshot already contains the mutation. Use `ctx.edit(obj)` *before* changing it.

## "Cannot start a write while this thread holds a read block"

You called `db.write(...)` or `db.beginWrite()` inside `db.read(...)`. A read lock cannot be
upgraded to a write lock, so this would deadlock against itself; the engine throws instead. Move
the write outside the read block.

## "Could not obtain access to jdk.internal.misc.Unsafe"

Your `DbCommand` or `DbQuery` is a **record**. EclipseStore's serializer reaches fields directly,
and the JVM refuses that for records unless started with
`--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`.

Use a plain class with public fields and a public no-arg constructor. The failure appears at the
first remote call, not at compile time, so it looks like a networking problem.

## A second process cannot open the store

By design: exactly one JVM owns a store. Either that is what you want and the second process should
be a client (`ZeroZDbNode` with `AUTO_SERVER` or `CLIENT_ONLY`), or a previous process did not shut
down. An OS file lock is released when a process dies, so a genuine crash does not leave a store
wedged; a still-running process does.

## A field is null after a release that renamed it

Schema matching is **strict** by default: only same-name fields are matched, so a renamed field
arrives unset rather than being filled from whatever else looks similar. Declare the rename:

```java
SchemaEvolution.strict().rename("com.x.Product#sku", "com.x.Product#code")
```

This is deliberate. EclipseStore's own lenient matching pairs leftover fields *by type*, so
removing one field and adding an unrelated one of the same type silently moves the old value into
the new field — wrong data with no error. `SchemaEvolution.lenient()` restores that behaviour if
you need it.

## The build fails with ROLLBACK_BREAKING

`SchemaCompatibility` found a model change that would prevent rolling back to the previous release
— a removed field, a changed type, or a removed class. Once the new release rewrites a record, the
old build cannot read it.

Either the change was accidental and should be reverted, or it was intended, in which case
regenerate the baseline in the same commit so the schema change is reviewed like any other API
change. A `CRITICAL` finding is different: a field was removed *and* another of the same type
added, which is the pattern that silently moves data. Confirm it is a deliberate rename.

## A client sees stale data

`node.localReads()` is a replica, refreshed the instant the owner commits but still trailing by
about one round trip. That is the trade for reading at heap speed. When a read must be exactly
current — permission checks especially — use `node.query(...)`, which always executes on the owner.

## Durability settings seem to have no effect

`ZeroZDb.attach(manager)` uses the storage configuration of the manager you handed it. Durability
and schema-evolution settings passed to `ZeroZDb.open(...)` do not apply, because the manager
already exists. Open the store through ZeroZ DB if you want it to control those.

## The server refuses to start

Binding to anything other than loopback without a secret is refused deliberately: a reachable
server with no authentication lets anyone who can open a socket read and write your data. Call
`secret(...)`, and add `tls(...)` for anything off-host.

## Writes are slower than expected

`Durability.SYNC` is the default and adds the fsync EclipseStore does not perform itself, which
costs roughly 10× throughput (~280 commits/s versus ~2.4k on a dev laptop). That buys survival of
power loss, not just process death. Use `OS_BUFFERED` for bulk imports and rebuildable data, where
losing the last second of writes is acceptable.

Also note writes are serialized per store — one writer at a time is the design. Stores are
independent, so more stores means more concurrent writers.

## The process holds far more memory than expected

There is no store eviction yet. A process that opens stores lazily and never closes them holds them
all. Close what you no longer need, and note that EclipseStore `Lazy` references are what keep a
single store's footprint proportional to what you actually touch.
