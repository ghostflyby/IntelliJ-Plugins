# TypesafeConventionsIntegration TODO

Status: In Progress Last Updated: 2026-07-25

## Compatibility Hardening

1. Add Groovy DSL Find Usages and rename support for contributed catalogs.
2. Add real Gradle sync integration coverage for `versions`, `bundles`, and
   `plugins` accessors.
3. Track IntelliJ changes to the experimental Gradle sync and Workspace Model APIs used by the integration.

## Done Criteria

1. Groovy catalog aliases support the same navigation, usage, and rename workflow as Kotlin aliases.
2. Every version catalog section has real synced-build integration coverage.
3. Experimental Gradle API changes are reviewed on each IntelliJ platform upgrade and recorded with an explicit
   migration decision.
