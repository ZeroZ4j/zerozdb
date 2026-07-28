# Contributing to ZeroZ DB

Thank you for considering a contribution. This is a young database, and the most valuable
contributions are usually the ones that show something does not behave as documented.

## Code of Conduct

Standard professional conduct is expected. Be welcoming and respectful.

## How can I contribute?

### Reporting bugs

Open an issue on the [GitHub repository](https://github.com/ZeroZ4j/zerozdb/issues). For anything
involving data — a change that did not persist, a rollback that did not restore, a value that
appeared where it should not — please include the store's durability mode, whether the write was in
a write-block, and which objects were enlisted. Those three answers identify most reports.

A failing test is the best possible bug report.

### Suggesting enhancements

Open an issue. Note that some absences are deliberate rather than missing: there is no query
language, no ORM layer, and no support for two JVMs writing one store concurrently. The reasoning
for each is in [docs/Feasibility-And-Design.md](docs/Feasibility-And-Design.md), and disagreeing
with it is welcome — but engaging with the argument is more useful than restating the request.

### Pull requests

1. Fork the repo and branch from `main`.
2. Add tests. Name them as sentences about behaviour, not about implementation.
3. Update documentation for any API change, including
   [docs/reference/limitations.md](docs/reference/limitations.md) if the change adds or removes a
   limitation.
4. Ensure `mvn clean test` passes — all of it, including the multi-process harnesses. They are slow
   because they spawn JVMs and kill them; that is the point.
5. Include the Apache 2.0 licence header on new source files. Copy it from any existing file; it
   carries project and author attribution and is not boilerplate to trim.
6. Open the pull request.

### A note on comments

Several places in this codebase carry a comment explaining why something looks wrong but is not —
the fair lock, strict schema matching, `writeResult` existing separately from `write`. Those record
decisions that were reversed once already. Please do not remove them, and please add one when your
change is likely to look like a mistake to the next reader.

## Schema changes

If your change alters the persistent shape of any class, the build will tell you: the schema
compatibility gate fails on anything that would break rollback to a previous release. If the change
is intended, regenerate the baseline in the same commit so the schema diff is reviewed alongside the
code.

## License

By contributing, you agree that your contributions will be licensed under the Apache 2.0 License.
