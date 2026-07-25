# Gradle Typesafe Conventions TOML Navigation

Status: Completed Completed: 2026-07-25

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
treated as equivalent accessor separators, including Gradle's case-insensitive first character after a separator.

The optional Kotlin descriptor registers the reference provider, goto declaration handler, references searcher, and
catalog use-scope enlarger only when the Kotlin plugin is available. The main descriptor retains the K2 compatibility
marker required before optional Kotlin configuration is loaded.

## User-Facing Behavior

- Kotlin catalog accessors resolve to the concrete `TomlKeyValue` declaration.
- Goto Declaration targets the exact catalog entry.
- Find Usages filters candidates by their resolved catalog file and entry, so catalogs with identical aliases do not
  cross-match.
- Renaming a TOML alias updates the matching Kotlin Gradle DSL accessors.
- Existing Groovy catalog navigation remains unchanged.

## Verification

Coverage includes focused TOML PSI tests and real Gradle sync tests for
`buildSrc` and included build logic. The integration tests directly inspect
`KtDotQualifiedExpression.references`, exercise registered goto handlers, perform `ReferencesSearch`, and run
`RenameProcessor` from a TOML key segment for both precompiled script and binary Kotlin convention plugins.
