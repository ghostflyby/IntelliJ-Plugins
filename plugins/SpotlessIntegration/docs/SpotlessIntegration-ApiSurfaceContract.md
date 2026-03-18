# SpotlessIntegration API Surface Contract

## Scope

This note records the intentionally public API surface of `SpotlessIntegration` after the async lifecycle refactor work.

The goal of this phase is not to minimize visibility at any cost. The goal is to keep only the types that must cross
plugin
boundaries public, document why they remain public, and avoid accidental ABI drift.

## Public Types Kept Intentionally

### `dev.ghostflyby.spotless.Spotless`

`Spotless` remains public because it is exposed as an application service interface from `plugin.xml`.

Contract notes:

- `format(...)` is the main integration entry point and returns `SpotlessFormatResult`.
- `canFormat(...)` is a strict daemon-backed dry-run check.
- `canFormatSync(...)` intentionally preserves the same strict semantics for synchronous formatter-selection paths.
- `releaseDaemon(...)` remains public for provider implementations that need explicit daemon cleanup.

### `dev.ghostflyby.spotless.SpotlessDaemonProvider`

`SpotlessDaemonProvider` remains public because it is the interface of a public extension point declared in
`plugin.xml`.

Contract notes:

- implementors own daemon startup and external-project resolution;
- `afterDaemonStopped(...)` is a best-effort lifecycle callback after the core HTTP stop contract runs.

### `dev.ghostflyby.spotless.SpotlessDaemonHost`

`SpotlessDaemonHost` remains public because provider implementations return it and the `Spotless` service consumes it.

The concrete `Localhost` and `Unix` variants are part of that contract and therefore stay public as well.

### `dev.ghostflyby.spotless.SpotlessFormatResult`

`SpotlessFormatResult` remains public because it is returned by the public `Spotless.format(...)` API.

## Internal-Only Hooks

The following members are intentionally internal and are not part of the public ABI:

- `SpotlessImpl.http`
- `SpotlessImpl.daemonProviderLookup`

They exist only to support focused tests for daemon health checks and failure handling without widening the public
service
constructor or relying on internal IntelliJ formatter APIs.

## ABI Decision

No public type was removed or narrowed in this phase.

This is intentional:

- the current public surface is required by the service and extension-point contracts;
- the compatibility risk of shrinking it now is higher than the benefit;
- behavior semantics were clarified in code docs and regression tests instead.
