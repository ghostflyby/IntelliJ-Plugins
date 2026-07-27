# Gradle Typesafe Conventions TOML Navigation

Status: Completed Completed: 2026-07-27

## Scope

The plugin contributes version catalog models from builds applying
`dev.panuszewski.typesafe-conventions`, then provides TOML navigation for the generated Kotlin and Groovy Gradle DSL
accessors in `buildSrc` and included build logic.

## Kotlin Integration

Kotlin catalog resolution now uses plugin-owned implementations built on public Kotlin PSI, TOML PSI, and IntelliJ
search APIs. It no longer instantiates
`KtTomlVersionCatalogReference` or calls APIs from the internal
`com.intellij.gradle.java.toml` package.

Catalog accessors are recognized through the imported Gradle build and catalog model rather than a `.gradle.kts`
filename check. This covers both precompiled script plugins and binary convention plugins implemented in ordinary `.kt`
sources.

The local resolver supports the `libraries`, `versions`, `bundles`, and
`plugins` sections in standard tables, top-level dotted keys, and inline tables. Dots, dashes, and underscores are
treated as equivalent accessor separators, including Gradle's case-insensitive first character after a separator. Each
Kotlin selector keeps the complete normalized alias and its target TOML segment index, allowing navigation and rename to
preserve every unaffected dotted segment.

The optional Kotlin descriptor registers the reference provider, goto declaration handler, references searcher, and
catalog use-scope enlarger only when the Kotlin plugin is available. The main descriptor retains the K2 compatibility
marker required before optional Kotlin configuration is loaded.

## User-Facing Behavior

- Kotlin catalog accessors resolve to the concrete `TomlKeySegment` declaration.
- Goto Declaration targets the exact catalog key segment under the caret.
- Find Usages filters candidates by their resolved catalog file and entry, so catalogs with identical aliases do not
  cross-match.
- Renaming from either a TOML key segment or Kotlin usage updates only the matching selector slice and preserves the
  remaining dotted alias.
- Local variables that shadow catalog roots and programmatic-only aliases retain their native Kotlin references without
  an additional unresolved catalog reference.
- Existing Groovy catalog navigation remains unchanged.

## Sync State

The tooling model distinguishes disabled builds, complete catalog collection, and incomplete collection with
serializable diagnostics. A complete model may replace the state for its linked Gradle root; an incomplete or missing
model leaves the last-known-good state intact and records warnings in the sync log.

Committed build URLs are persisted per normalized linked project path. Candidates remain in memory until a successful
project import, failed or cancelled imports discard only their pending candidate, and unlink removes only the affected
root. Catalog lookup also requires the persisted build URL to exist in the current Workspace Model snapshot, preventing
stale state from creating references after unlink or workspace migration.

## Performance

Catalog lookup uses an immutable project-level index keyed by Workspace Model snapshot identity and the committed sync
state modification count. Repeated resolution against an unchanged project reuses the index instead of traversing all
Gradle build entities, while a workspace or committed-state change rebuilds it from the cross-validated model.

Each TOML catalog file keeps a PSI-dependent alias index by section and normalized alias. Kotlin reference creation
checks the catalog and alias indexes before resolving the Kotlin root declaration, and created references retain their
resolved TOML segment for the rest of the occurrence-processing path.

Find Usages registers an indexed word request whose scope is the intersection of the user-selected scope and the Gradle
build roots associated with the target catalog. The TOML use-scope enlargement uses the same roots, preventing unrelated
project files from becoming search candidates.

## Verification

Coverage includes focused TOML PSI tests and real Gradle sync tests for
`buildSrc` and included build logic. The integration tests directly inspect
`KtDotQualifiedExpression.references`, exercise registered goto handlers, perform `ReferencesSearch`, and run
`RenameProcessor` from TOML and Kotlin segments for both precompiled script and binary Kotlin convention plugins. State
coverage includes sequential linked roots, successful disable, failed and cancelled imports, null-path commits, unlink
cleanup, restart recovery, and rejection of incomplete Workspace Model candidates. Structural performance coverage
verifies Workspace Model index reuse and invalidation, TOML PSI cache invalidation, build-root search scoping, and
catalog refresh deduplication without relying on absolute timing assertions.
